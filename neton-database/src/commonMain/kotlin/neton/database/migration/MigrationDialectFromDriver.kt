package neton.database.migration

import neton.core.module.MigrationDialect
import neton.database.config.DatabaseDriver

/**
 * 把 [DatabaseDriver](neton-database 层概念)映射到 [MigrationDialect](neton-core 层概念)。
 *
 * 留在 neton-database 是因为 DatabaseDriver 属于这一层 —— neton-core 不应该知道
 * "sqlx4k 有 driver" 这种实现细节。`MigrationDialect.fromDriver(...)` 被 application Main
 * 在装配 MigrationConfig 时调用。
 *
 * `MEMORY` driver(开发/演示)走 SQLITE,因为它的 schema 表达和 sqlite 一致。
 */
fun MigrationDialect.Companion.fromDriver(driver: DatabaseDriver): MigrationDialect = when (driver) {
    DatabaseDriver.POSTGRESQL -> MigrationDialect.POSTGRESQL
    DatabaseDriver.MYSQL -> MigrationDialect.MYSQL
    DatabaseDriver.SQLITE, DatabaseDriver.MEMORY -> MigrationDialect.SQLITE
}
