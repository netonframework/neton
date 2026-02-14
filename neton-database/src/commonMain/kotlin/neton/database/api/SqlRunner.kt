package neton.database.api

/**
 * 轻量 Row 抽象，业务层取值用，不暴露 sqlx4k 的 ResultSet.Row。
 * 由 adapter 实现适配，便于未来换引擎。
 */
interface Row {
    fun long(name: String): Long
    fun longOrNull(name: String): Long?
    fun string(name: String): String
    fun stringOrNull(name: String): String?
    fun int(name: String): Int
    fun intOrNull(name: String): Int?
    fun double(name: String): Double
    fun doubleOrNull(name: String): Double?
    fun boolean(name: String): Boolean
    fun booleanOrNull(name: String): Boolean?
}

/**
 * SQL 执行器，供 Repository 做联查/聚合。
 * 由 neton-database.adapter.sqlx 实现，不暴露 sqlx4k 类型。
 */
interface SqlRunner {
    suspend fun fetchAll(sql: String, params: Map<String, Any?> = emptyMap()): List<Row>
    suspend fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Long
}
