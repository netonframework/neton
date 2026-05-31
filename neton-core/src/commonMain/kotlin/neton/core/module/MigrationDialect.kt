package neton.core.module

/**
 * Migration 方言枚举,平台无关。落在 neton-core 是为了让 [ModuleInitializer.migrations]
 * 不需要依赖 neton-database。具体的 driver ↔ dialect 映射(`fromDriver(DatabaseDriver)`)
 * 留在 neton-database,因为 DatabaseDriver 属于那一层。
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
    }
}
