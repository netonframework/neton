package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.config.PostgresUriInfo

// NETON-DB-VARIANT postgres: 只编译进 -Pneton.database.driver=postgres (默认) 的 build.
// 其它 driver 类型在本 variant 下无法实例化 → 抛清晰错误.
internal fun createSqlxDriver(config: DatabaseConfig): QueryExecutor = when (config.driver) {
    DatabaseDriver.POSTGRESQL -> {
        val info = config.parseUri() as PostgresUriInfo
        PostgreSQL(
            url = "postgresql://${info.host}:${info.port}/${info.database}",
            username = info.username,
            password = info.password,
        )
    }
    else -> error(
        "NETON-DB-VARIANT mismatch: 当前 build variant = postgres, " +
            "但 config.driver = ${config.driver}. " +
            "重新编译并 -Pneton.database.driver=${config.driver.name.lowercase()} 切换 variant, " +
            "或修改 database.conf 使用 POSTGRESQL."
    )
}
