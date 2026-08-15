package neton.database.api

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import neton.database.sql.Dialect
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Provider-neutral database execution session. */
interface DbSession {
    val dialect: Dialect

    suspend fun query(sql: String, params: Map<String, Any?> = emptyMap()): List<Row>

    suspend fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Long
}

/** Resolves the root or coroutine-scoped transaction session. */
interface DbSessionProvider {
    val dialect: Dialect

    suspend fun current(): DbSession

    suspend fun <R> transaction(block: suspend () -> R): R

    /** 当前协程是否处在 [transaction] 块内。 */
    suspend fun inTransaction(): Boolean
}

internal interface DbTransactionRunner {
    suspend fun <R> run(block: suspend (DbSession) -> R): R
}

/** Shared coroutine-scoping behavior used by every database adapter. */
internal class CoroutineDbSessionProvider(
    private val root: DbSession,
    private val transactions: DbTransactionRunner,
) : DbSessionProvider {
    override val dialect: Dialect get() = root.dialect

    override suspend fun current(): DbSession =
        currentCoroutineContext()[TransactionSession]?.session ?: root

    override suspend fun inTransaction(): Boolean =
        currentCoroutineContext()[TransactionSession] != null

    override suspend fun <R> transaction(block: suspend () -> R): R {
        if (currentCoroutineContext()[TransactionSession] != null) return block()
        return transactions.run { transactionSession ->
            val scoped = ScopedDbSession(transactionSession)
            try {
                withContext(TransactionSession(scoped)) { block() }
            } finally {
                scoped.close()
            }
        }
    }
}

private class ScopedDbSession(
    private val delegate: DbSession,
) : DbSession {
    private var active = true

    override val dialect: Dialect get() = delegate.dialect

    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> {
        check(active) { "Transaction session is no longer active" }
        return delegate.query(sql, params)
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        check(active) { "Transaction session is no longer active" }
        return delegate.execute(sql, params)
    }

    fun close() {
        active = false
    }
}

private class TransactionSession(
    val session: DbSession,
) : AbstractCoroutineContextElement(TransactionSession) {
    companion object Key : CoroutineContext.Key<TransactionSession>
}
