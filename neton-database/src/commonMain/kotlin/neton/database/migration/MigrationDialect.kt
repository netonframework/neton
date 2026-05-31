package neton.database.migration

import neton.database.config.DatabaseDriver

/**
 * Migration engine 用的方言枚举。与 [DatabaseDriver] 一一对应(MEMORY 没有 migration 语义,
 * 走 SQLITE)。
 */
enum class MigrationDialect(val canonical: String) {
    POSTGRESQL("postgresql"),
    MYSQL("mysql"),
    SQLITE("sqlite");

    companion object {
        fun fromString(s: String): MigrationDialect? = when (s.lowercase()) {
            "postgresql", "postgres", "pg" -> POSTGRESQL
            "mysql" -> MYSQL
            "sqlite" -> SQLITE
            else -> null
        }

        fun fromDriver(driver: DatabaseDriver): MigrationDialect = when (driver) {
            DatabaseDriver.POSTGRESQL -> POSTGRESQL
            DatabaseDriver.MYSQL -> MYSQL
            DatabaseDriver.SQLITE, DatabaseDriver.MEMORY -> SQLITE
        }
    }
}
