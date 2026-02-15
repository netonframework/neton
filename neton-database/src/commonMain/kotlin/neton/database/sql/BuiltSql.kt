package neton.database.sql

/**
 * 构建后的 SQL 与参数列表，供执行层（如 sqlx）使用。
 */
data class BuiltSql(val sql: String, val args: List<Any?>)
