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
        val rows = executor.fetchAll(statement(sql, params)).getOrThrow()
        return rows.map(::SqlxRow)
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long =
        executor.execute(statement(sql, params)).getOrThrow()
}

private class TransactionBackedSession(
    private val transaction: Transaction,
    override val dialect: Dialect,
) : DbSession {
    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> {
        val rows = transaction.fetchAll(statement(sql, params)).getOrThrow()
        return rows.map(::SqlxRow)
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long =
        transaction.execute(statement(sql, params)).getOrThrow()
}

private fun statement(sql: String, params: Map<String, Any?>): Statement =
    params.entries.fold(Statement.create(sql)) { current, (name, value) -> current.bind(name, value) }

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
