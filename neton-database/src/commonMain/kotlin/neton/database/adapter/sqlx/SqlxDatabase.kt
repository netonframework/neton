package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.github.smyrgeorge.sqlx4k.mysql.MySQL
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.config.MysqlUriInfo
import neton.database.config.PostgresUriInfo

/**
 * 持有 sqlx4k 数据源（SQLite / PostgreSQL / MySQL），根据 database.conf 配置自动选择驱动
 */
object SqlxDatabase {
    private var db: QueryExecutor? = null
    private var driver: DatabaseDriver = DatabaseDriver.MEMORY

    fun initialize(config: DatabaseConfig) {
        driver = config.driver
        db = when (config.driver) {
            DatabaseDriver.MEMORY -> SQLite(url = "sqlite::memory:")
            DatabaseDriver.SQLITE -> SQLite(
                url = config.uri.takeIf { it.startsWith("sqlite") } ?: "sqlite://${config.uri}"
            )
            DatabaseDriver.POSTGRESQL -> {
                val info = config.parseUri() as PostgresUriInfo
                PostgreSQL(
                    url = "postgresql://${info.host}:${info.port}/${info.database}",
                    username = info.username,
                    password = info.password
                )
            }
            DatabaseDriver.MYSQL -> {
                val info = config.parseUri() as MysqlUriInfo
                MySQL(
                    url = "mysql://${info.host}:${info.port}/${info.database}",
                    username = info.username,
                    password = info.password
                )
            }
        }
    }

    fun require(): QueryExecutor = db ?: throw IllegalStateException("SqlxDatabase 未初始化，请先调用 database { }")

    fun currentDriver(): DatabaseDriver = driver

    /** 执行 DDL，由业务按需调用 */
    suspend fun executeDdl(ddl: String) {
        require().execute(ddl).getOrThrow()
    }
}
