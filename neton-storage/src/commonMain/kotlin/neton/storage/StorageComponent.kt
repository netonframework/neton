package neton.storage

import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.storage.internal.DefaultStorageManager
import neton.storage.internal.SourceConfigParser
import neton.storage.local.LocalStorageOperator
import neton.storage.s3.S3StorageOperator
import io.ktor.client.*

object StorageComponent : NetonComponent<StorageConfig> {

    override fun defaultConfig(): StorageConfig = StorageConfig()

    override suspend fun init(ctx: NetonContext, config: StorageConfig) {
        val logger = ctx.getOrNull(LoggerFactory::class)?.get("neton.storage")

        val fileSources = loadFromFile(ctx)
        val dslSources = config.sources.toList()
        val merged = if (fileSources.isNotEmpty() || dslSources.isNotEmpty()) {
            SourceConfigParser.mergeSources(dslSources, fileSources)
        } else {
            dslSources.ifEmpty { listOf(SourceConfig()) }
        }

        SourceConfigParser.validateSources(merged)

        val operators = merged.associate { src ->
            src.name to createOperator(src, logger)
        }

        val manager = DefaultStorageManager(operators)
        ctx.bind(StorageManager::class, manager)
        ctx.bind(StorageOperator::class, manager.default())

        logger?.info(
            "Storage initialized", mapOf(
                "sources" to merged.map { "${it.name}(${it.type})" }.joinToString(", ")
            )
        )
    }

    override suspend fun stop(ctx: NetonContext) {
        // HttpClient cleanup could happen here if needed
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile(ctx: NetonContext): List<SourceConfig> {
        val raw = ConfigLoader.loadModuleConfig(
            "storage",
            configPath = "config",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args
        ) ?: return emptyList()

        return try {
            SourceConfigParser.parseSourceConfigs(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun createOperator(src: SourceConfig, logger: Logger?): StorageOperator {
        return when (src.type) {
            "local" -> LocalStorageOperator(src.name, src.basePath, logger)
            "s3" -> {
                val httpClient = HttpClient()
                S3StorageOperator(
                    name = src.name,
                    endpoint = src.endpoint,
                    region = src.region,
                    bucket = src.bucket,
                    accessKey = src.accessKey,
                    secretKey = src.secretKey,
                    pathStyle = src.pathStyle,
                    httpClient = httpClient,
                    logger = logger
                )
            }

            else -> throw IllegalStateException("Unknown storage type '${src.type}' for source '${src.name}'")
        }
    }
}
