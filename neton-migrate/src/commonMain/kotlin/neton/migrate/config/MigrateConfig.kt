package neton.migrate.config

import neton.core.config.ConfigLoader
import neton.migrate.cli.CliOptions

/**
 * 解析后的迁移配置 — 仅 CLI / config 文件两个来源合并的结果。
 */
data class MigrateConfig(
    val driver: Driver,
    val uri: String,
    val sqlDir: String,
    val historyTable: String
)

enum class Driver(val canonical: String) {
    SQLITE("sqlite"),
    POSTGRESQL("postgresql"),
    MYSQL("mysql");

    companion object {
        fun fromString(s: String): Driver? = when (s.lowercase()) {
            "sqlite" -> SQLITE
            "postgresql", "postgres", "pg" -> POSTGRESQL
            "mysql" -> MYSQL
            else -> null
        }
    }
}

object MigrateConfigResolver {

    /**
     * 决策 D1：CLI flag > config/database.conf [default] 段。
     * 缺 sqlDir 直接报错（v0.1 必须显式指定，决策 D2）。
     */
    fun resolve(opts: CliOptions): Result {
        val sqlDir = opts.sqlDir
            ?: return Result.MissingArg("--dir is required (point to sql/{dialect}/ directory)")

        // 尝试加载 database.conf [default]
        val raw = try {
            ConfigLoader.loadModuleConfig(
                moduleName = "database",
                configPath = opts.configPath,
                environment = null
            )
        } catch (_: Exception) {
            null
        }

        val defaultSection = raw?.get("default") as? Map<*, *>

        val driverStr = opts.driver
            ?: (defaultSection?.get("driver") as? String)
            ?: return Result.MissingArg("--driver is required (or set [default].driver in database.conf)")

        val driver = Driver.fromString(driverStr)
            ?: return Result.MissingArg("invalid driver: $driverStr (use sqlite | postgresql | mysql)")

        val uri = opts.uri
            ?: (defaultSection?.get("uri") as? String)
            ?: return Result.MissingArg("--uri is required (or set [default].uri in database.conf)")

        return Result.Ok(
            MigrateConfig(
                driver = driver,
                uri = uri,
                sqlDir = sqlDir,
                historyTable = opts.historyTable
            )
        )
    }

    sealed class Result {
        data class Ok(val config: MigrateConfig) : Result()
        data class MissingArg(val message: String) : Result()
    }
}
