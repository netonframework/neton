package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import neton.database.api.CoroutineDbSessionProvider
import neton.database.api.DbSession
import neton.database.api.DbSessionProvider
import neton.database.api.DbTransactionRunner
import neton.database.api.Row
import neton.database.sql.Dialect

internal class SqlxSessionProvider(
    root: QueryExecutor,
    dialect: Dialect,
) : DbSessionProvider by CoroutineDbSessionProvider(
    root = ExecutorSession(root, dialect),
    transactions = SqlxTransactionRunner(root, dialect),
)

private class SqlxTransactionRunner(
    root: QueryExecutor,
    private val dialect: Dialect,
) : DbTransactionRunner {
    private val transactional = root as? QueryExecutor.Transactional
        ?: error("Database driver does not support transactions")

    override suspend fun <R> run(block: suspend (DbSession) -> R): R {
        val transaction = transactional.begin().getOrThrow()
        return try {
            val result = block(TransactionBackedSession(transaction, dialect))
            transaction.commit().getOrThrow()
            result
        } catch (error: Throwable) {
            transaction.rollback().exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}

private class ExecutorSession(
    private val executor: QueryExecutor,
    override val dialect: Dialect,
) : DbSession {
    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> {
        val rows = executor.fetchAll(statement(sql, params, dialect)).getOrThrow()
        return rows.map(::SqlxRow)
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long =
        executor.execute(statement(sql, params, dialect)).getOrThrow()
}

private class TransactionBackedSession(
    private val transaction: Transaction,
    override val dialect: Dialect,
) : DbSession {
    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> {
        val rows = transaction.fetchAll(statement(sql, params, dialect)).getOrThrow()
        return rows.map(::SqlxRow)
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long =
        transaction.execute(statement(sql, params, dialect)).getOrThrow()
}

private fun statement(sql: String, params: Map<String, Any?>, dialect: Dialect): Statement {
    val renderedSql = if (dialect.name == "postgres") applyPostgresScalarCasts(sql, params) else sql
    return params.entries.fold(Statement.create(renderedSql)) { current, (name, value) -> current.bind(name, value) }
}

internal fun applyPostgresScalarCasts(sql: String, params: Map<String, Any?>): String {
    if (params.isEmpty()) return sql

    val out = StringBuilder(sql.length + params.size * 6)
    var i = 0
    while (i < sql.length) {
        when {
            sql.startsWith("--", i) -> {
                val end = sql.indexOf('\n', i + 2).let { if (it == -1) sql.length else it + 1 }
                out.append(sql, i, end)
                i = end
            }
            sql.startsWith("/*", i) -> {
                val end = sql.indexOf("*/", i + 2).let { if (it == -1) sql.length else it + 2 }
                out.append(sql, i, end)
                i = end
            }
            sql[i] == '\'' -> {
                i = appendQuoted(sql, i, out, '\'')
            }
            sql[i] == '"' -> {
                i = appendQuoted(sql, i, out, '"')
            }
            sql[i] == '$' -> {
                val delimiter = dollarQuoteDelimiter(sql, i)
                if (delimiter != null) {
                    val bodyEnd = sql.indexOf(delimiter, i + delimiter.length)
                    val end = if (bodyEnd == -1) sql.length else bodyEnd + delimiter.length
                    out.append(sql, i, end)
                    i = end
                } else {
                    out.append(sql[i++])
                }
            }
            sql[i] == ':' && i + 1 < sql.length && sql[i + 1] == ':' -> {
                out.append("::")
                i += 2
            }
            sql[i] == ':' && i + 1 < sql.length && isIdentStart(sql[i + 1]) -> {
                var end = i + 2
                while (end < sql.length && isIdentPart(sql[end])) end++
                val name = sql.substring(i + 1, end)
                out.append(sql, i, end)
                val alreadyCasted = end + 1 < sql.length && sql[end] == ':' && sql[end + 1] == ':'
                if (!alreadyCasted) postgresScalarCast(params[name])?.let(out::append)
                i = end
            }
            else -> out.append(sql[i++])
        }
    }
    return out.toString()
}

private fun appendQuoted(sql: String, start: Int, out: StringBuilder, quote: Char): Int {
    var i = start
    out.append(sql[i++])
    while (i < sql.length) {
        out.append(sql[i])
        if (sql[i] == quote) {
            if (i + 1 < sql.length && sql[i + 1] == quote) {
                out.append(sql[i + 1])
                i += 2
                continue
            }
            return i + 1
        }
        i++
    }
    return i
}

private fun dollarQuoteDelimiter(sql: String, start: Int): String? {
    var i = start + 1
    while (i < sql.length && isIdentPart(sql[i])) i++
    if (i < sql.length && sql[i] == '$') return sql.substring(start, i + 1)
    return null
}

private fun postgresScalarCast(value: Any?): String? = when (value) {
    is Byte, is Short -> "::int2"
    is Int -> "::int4"
    is Long -> "::int8"
    is Float -> "::float4"
    is Double -> "::float8"
    is Boolean -> "::boolean"
    else -> null
}

private fun isIdentStart(c: Char): Boolean = c == '_' || c in 'A'..'Z' || c in 'a'..'z'
private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c in '0'..'9'

internal class SqlxRow(private val row: ResultSet.Row) : Row {
    private fun stringValue(name: String): String? = row.get(name).asStringOrNull()

    override fun long(name: String): Long =
        stringValue(name)?.toLongOrNull() ?: error("null or invalid long: $name")

    override fun longOrNull(name: String): Long? = stringValue(name)?.toLongOrNull()
    override fun string(name: String): String = stringValue(name) ?: error("null string: $name")
    override fun stringOrNull(name: String): String? = stringValue(name)

    override fun int(name: String): Int =
        stringValue(name)?.toIntOrNull() ?: error("null or invalid int: $name")

    override fun intOrNull(name: String): Int? = stringValue(name)?.toIntOrNull()

    override fun double(name: String): Double =
        stringValue(name)?.toDoubleOrNull() ?: error("null or invalid double: $name")

    override fun doubleOrNull(name: String): Double? = stringValue(name)?.toDoubleOrNull()

    override fun boolean(name: String): Boolean =
        stringValue(name)?.toBooleanStrictOrNull() ?: error("null or invalid boolean: $name")

    override fun booleanOrNull(name: String): Boolean? = stringValue(name)?.toBooleanStrictOrNull()
    override fun bytes(name: String): ByteArray = string(name).encodeToByteArray()
    override fun bytesOrNull(name: String): ByteArray? = stringValue(name)?.encodeToByteArray()
}
