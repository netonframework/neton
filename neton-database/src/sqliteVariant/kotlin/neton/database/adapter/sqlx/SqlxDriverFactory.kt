package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver

// NETON-DB-VARIANT sqlite: 只编译进 -Pneton.database.driver=sqlite 的 build.
// 用于 commonTest / dev MEMORY 场景; production 通常不用 sqlite variant.
internal fun createSqlxDriver(config: DatabaseConfig): SqlxDriverHandle = when (config.driver) {
    DatabaseDriver.MEMORY -> SQLite(url = "sqlite::memory:").let { driver ->
        SqlxDriverHandle(driver) { driver.close() }
    }
    DatabaseDriver.SQLITE -> SQLite(
        url = config.uri.takeIf { it.startsWith("sqlite") } ?: "sqlite://${config.uri}"
    ).let { driver -> SqlxDriverHandle(driver) { driver.close() } }
    else -> error(
        "NETON-DB-VARIANT mismatch: 当前 build variant = sqlite, " +
            "但 config.driver = ${config.driver}. " +
            "重新编译并 -Pneton.database.driver=${config.driver.name.lowercase()} 切换 variant, " +
            "或修改 database.conf 使用 SQLITE / MEMORY."
    )
}
