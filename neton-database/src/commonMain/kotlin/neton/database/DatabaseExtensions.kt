package neton.database

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.logging.Logger
import neton.logging.LoggerFactory
import neton.database.config.*
import neton.database.query.DefaultQueryExecutor
import neton.database.query.EntityPersistence
import neton.database.query.QueryRuntime
import neton.database.query.TableRegistry
import neton.database.api.SqlRunner
import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.adapter.sqlx.SqlxSqlRunner
import kotlin.reflect.KClass

/** 模块内 Logger 注入点，由 DatabaseComponent.init 设置 */
internal object DatabaseLog {
    var log: Logger? = null
}

/** Database install DSL 的配置对象 */
class DatabaseInstallConfig {
    /** 可选：在 onInit 时安装生成的 Repository 到 ctx（由 KSP 生成 installGeneratedRepositories） */
    var onRepositoriesInstall: ((neton.core.component.NetonContext) -> Unit)? = null

    /**
     * 可选：v2 Query API 的 Table 查找函数。
     * 设置后会自动注入 QueryRuntime.executor 和 EntityPersistence.saver，
     * 业务层即可使用 UserTable.query { } / get / save()（需 KSP 生成 Table）。
     */
    var tableRegistry: TableRegistry? = null
}

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
        SqlxDatabase.initialize(dbConfig)
        config.onRepositoriesInstall?.let { block ->
            block(ctx)
            log?.info("database.repositories.installed")
        }
        config.tableRegistry?.let { registry ->
            QueryRuntime.executor = DefaultQueryExecutor(registry)
            EntityPersistence.saver = { entity ->
                @Suppress("UNCHECKED_CAST")
                (registry((entity as Any)::class) as? Table<Any, *>)?.save(entity as Any)
            }
            log?.info("database.query_runtime.set")
        }
        log?.info("database.initialized")
    }

    /**
     * 加载数据库配置。
     * 文件名 = 命名空间：database.conf → config.database.*
     * 冻结：database.conf 仅允许 [default]（v1）或 [analytics] 等连接名（v3），禁止 [database]。
     */
    private fun loadDatabaseConfig(ctx: NetonContext, configMap: Map<String, Any>?, log: Logger?): DatabaseConfig {
        return try {
            val rawConfig = configMap ?: ConfigLoader.loadModuleConfig("database", configPath = "config", environment = ConfigLoader.resolveEnvironment(ctx.args), args = ctx.args)
            if (rawConfig == null) {
                log?.warn("database.config.missing", mapOf("fallback" to "sqlite::memory:"))
                return DatabaseConfig(driver = DatabaseDriver.MEMORY, uri = "sqlite::memory:", debug = true)
            }
            val databaseConfigMap = rawConfig["default"] as? Map<String, Any>
                ?: return DatabaseConfig(driver = DatabaseDriver.MEMORY, uri = "sqlite::memory:", debug = true)
            DatabaseConfig.fromMap(databaseConfigMap)
        } catch (e: Exception) {
            log?.warn("database.config.error", mapOf("message" to (e.message ?: "")))
            DatabaseConfig(driver = DatabaseDriver.MEMORY, uri = "sqlite::memory:", debug = true)
        }
    }
}

fun Neton.LaunchBuilder.database(block: DatabaseInstallConfig.() -> Unit = {}) = install(DatabaseComponent, block)

/** 获取 SqlRunner（供 Repository 做联查/聚合）。需先调用 database { } 初始化。 */
fun sqlRunner(): SqlRunner = SqlxSqlRunner
