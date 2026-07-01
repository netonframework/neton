package neton.database

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.database.config.*
import neton.database.api.DbContext
import neton.database.api.DbSessionProvider
import neton.database.adapter.sqlx.SqlxDatabase

/** 模块内 Logger 注入点，由 DatabaseComponent.init 设置 */
internal object DatabaseLog {
    var log: Logger? = null
}

/** Database install DSL configuration. Reserved for provider-neutral options. */
class DatabaseInstallConfig

/**
 * Database 组件 - 使用 sqlx4k 作为唯一底层
 */
object DatabaseComponent : NetonComponent<DatabaseInstallConfig> {

    override fun defaultConfig(): DatabaseInstallConfig = DatabaseInstallConfig()

    override suspend fun init(ctx: NetonContext, config: DatabaseInstallConfig) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.database")
        DatabaseLog.log = log
        log?.info("database.init", mapOf("engine" to "sqlx4k"))
        val dbConfig = loadDatabaseConfig(ctx, null, log)
        val validationErrors = dbConfig.validate()
        if (validationErrors.isNotEmpty()) {
            throw IllegalArgumentException("数据库配置无效: ${validationErrors.joinToString(", ")}")
        }
        val dbContext = SqlxDatabase.initialize(dbConfig)
        ctx.bind(DbContext::class, dbContext)
        ctx.bind(DbSessionProvider::class, SqlxDatabase.requireSessionProvider())
        log?.info("database.initialized")
    }

    override suspend fun stop(ctx: NetonContext) {
        SqlxDatabase.close()
        DatabaseLog.log = null
    }

    /**
     * 加载数据库配置。
     * 文件名 = 命名空间：database.conf → config.database.*
     * 冻结：database.conf 仅允许 [default]（v1）或 [analytics] 等连接名（v3），禁止 [database]。
     */
    private fun loadDatabaseConfig(ctx: NetonContext, configMap: Map<String, Any>?, log: Logger?): DatabaseConfig {
        try {
            val rawConfig = configMap ?: ConfigLoader.loadModuleConfig(
                "database",
                configPath = "config",
                environment = ConfigLoader.resolveEnvironment(ctx.args),
                args = ctx.args
            )
            checkNotNull(rawConfig) { "Missing required config/database.conf" }
            @Suppress("UNCHECKED_CAST")
            val databaseConfigMap = rawConfig["default"] as? Map<String, Any>
                ?: error("config/database.conf must contain a [default] table")
            return DatabaseConfig.fromMap(databaseConfigMap)
        } catch (error: Throwable) {
            log?.error(
                "database.config.invalid",
                mapOf("message" to (error.message ?: error::class.simpleName.orEmpty())),
                cause = error,
            )
            throw IllegalStateException("Invalid database configuration", error)
        }
    }
}

fun Neton.LaunchBuilder.database(block: DatabaseInstallConfig.() -> Unit = {}) = install(DatabaseComponent, block)

/** 获取 DbContext（Logic 层的 SQL 执行入口）。需先调用 database { } 初始化。 */
fun dbContext(): DbContext = SqlxDatabase.requireContext()
