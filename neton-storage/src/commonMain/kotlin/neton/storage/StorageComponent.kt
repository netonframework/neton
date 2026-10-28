package neton.storage

import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.core.config.readConfigFile
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.storage.internal.DefaultStorageManager
import neton.storage.internal.SourceConfigParser
import neton.storage.local.LocalStorageOperator
import neton.storage.s3.S3StorageOperator
import neton.http.client.HttpClient

object StorageComponent : NetonComponent<StorageConfig> {

    override fun defaultConfig(): StorageConfig = StorageConfig()

    override suspend fun init(ctx: NetonContext, config: StorageConfig) {
        val logger = ctx.getOrNull(LoggerFactory::class)?.get("neton.storage")

        val merged = resolveSources(loadFromFile(ctx), config.sources.toList())

        val operators = merged.associate { src ->
            src.name to createOperator(ctx, src, logger)
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
        (ctx.getOrNull(StorageManager::class) as? DefaultStorageManager)?.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromFile(ctx: NetonContext): Map<String, Any?>? {
        val environment = ConfigLoader.resolveEnvironment(ctx.args)
        val raw = ConfigLoader.loadModuleConfig(
            "storage",
            configPath = "config",
            environment = environment,
            args = ctx.args
        ) ?: return null
        // ConfigLoader returns an empty map for an absent module. An existing empty
        // file is still invalid storage configuration, not permission to fall back.
        val hasFile = readConfigFile("config/storage.conf") != null ||
            (environment != null && readConfigFile("config/storage.$environment.conf") != null)
        return raw.takeIf { it.isNotEmpty() || hasFile }
    }

    internal fun resolveSources(raw: Map<String, Any?>?, dsl: List<SourceConfig>): List<SourceConfig> {
        val file = raw?.let(SourceConfigParser::parseSourceConfigs).orEmpty()
        val merged = if (raw == null && dsl.isEmpty()) listOf(SourceConfig())
            else SourceConfigParser.mergeSources(dsl, file)
        SourceConfigParser.validateSources(merged)
        return merged
    }

    private fun createOperator(ctx: NetonContext, src: SourceConfig, logger: Logger?): StorageOperator {
        return when (src.type) {
            "local" -> LocalStorageOperator(src.name, src.basePath, logger, src.baseUrl)
            "s3" -> {
                // Borrowed, never created here: the application builds one client,
                // binds it, and closes it at shutdown. Same rule as neton-ai.
                val httpClient = ctx.getOrNull(HttpClient::class)
                    ?: throw IllegalStateException(
                        "storage source '${src.name}' is type s3 and needs an HttpClient bound in " +
                            "NetonContext. Build one with HttpClient.create { } and bind(HttpClient::class, it) " +
                            "before the storage component initialises.",
                    )
                S3StorageOperator(
                    name = src.name,
                    endpoint = src.endpoint,
                    region = src.region,
                    bucket = src.bucket,
                    accessKey = src.accessKey,
                    secretKey = src.secretKey,
                    pathStyle = src.pathStyle,
                    httpClient = httpClient,
                    logger = logger,
                    baseUrl = src.baseUrl,
                )
            }

            else -> throw IllegalStateException("Unknown storage type '${src.type}' for source '${src.name}'")
        }
    }
}
