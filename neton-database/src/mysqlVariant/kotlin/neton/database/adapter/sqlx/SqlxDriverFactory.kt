package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.mysql.MySQL
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.config.MysqlUriInfo

// NETON-DB-VARIANT mysql: 只编译进 -Pneton.database.driver=mysql 的 build.
internal fun createSqlxDriver(config: DatabaseConfig): QueryExecutor = when (config.driver) {
    DatabaseDriver.MYSQL -> {
        val info = config.parseUri() as MysqlUriInfo
        MySQL(
            url = "mysql://${info.host}:${info.port}/${info.database}",
            username = info.username,
            password = info.password,
        )
    }
    else -> error(
        "NETON-DB-VARIANT mismatch: 当前 build variant = mysql, " +
            "但 config.driver = ${config.driver}. " +
            "重新编译并 -Pneton.database.driver=${config.driver.name.lowercase()} 切换 variant, " +
            "或修改 database.conf 使用 MYSQL."
    )
}
