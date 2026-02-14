package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.api.Row
import neton.database.api.SqlRunner

/**
 * sqlx4k 适配的 SqlRunner 实现。
 */
object SqlxSqlRunner : SqlRunner {

    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
        val db = SqlxDatabase.require()
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        val rows = db.fetchAll(stmt).getOrThrow()
        return rows.map { SqlxRow(it) }
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        val db = SqlxDatabase.require()
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        return db.execute(stmt).getOrThrow()
    }
}

/**
 * 适配 sqlx4k ResultSet.Row 为 neton.database.api.Row。
 * sqlx4k Column 仅提供 asString/asStringOrNull，数值类型由本层解析。
 */
private class SqlxRow(private val row: io.github.smyrgeorge.sqlx4k.ResultSet.Row) : Row {
    private fun str(name: String): String? = row.get(name).asStringOrNull()

    override fun long(name: String): Long = str(name)?.toLongOrNull() ?: throw IllegalArgumentException("null or invalid long: $name")
    override fun longOrNull(name: String): Long? = str(name)?.toLongOrNull()
    override fun string(name: String): String = str(name) ?: throw IllegalArgumentException("null string: $name")
    override fun stringOrNull(name: String): String? = str(name)
    override fun int(name: String): Int = str(name)?.toIntOrNull() ?: throw IllegalArgumentException("null or invalid int: $name")
    override fun intOrNull(name: String): Int? = str(name)?.toIntOrNull()
    override fun double(name: String): Double = str(name)?.toDoubleOrNull() ?: throw IllegalArgumentException("null or invalid double: $name")
    override fun doubleOrNull(name: String): Double? = str(name)?.toDoubleOrNull()
    override fun boolean(name: String): Boolean = str(name)?.toBooleanStrictOrNull() ?: throw IllegalArgumentException("null or invalid boolean: $name")
    override fun booleanOrNull(name: String): Boolean? = str(name)?.toBooleanStrictOrNull()
}
