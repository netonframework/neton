package neton.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import neton.database.adapter.sqlx.SqlxDbContext
import neton.database.adapter.sqlx.SqlxTableAdapter
import neton.database.api.CoroutineDbSessionProvider
import neton.database.api.DbSession
import neton.database.api.DbTransactionRunner
import neton.database.api.EntityMapper
import neton.database.api.EntityMeta
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.dsl.QueryAst
import neton.database.sql.Dialect
import neton.database.sql.PostgresDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DbSessionProviderContractTest {
    @Test
    fun transactionScopesAllTablesToOneSession() = runTest {
        val root = RecordingSession("root")
        val transaction = RecordingSession("transaction")
        val runner = RecordingTransactionRunner(transaction)
        val provider = CoroutineDbSessionProvider(root, runner)
        val db = SqlxDbContext(provider)
        val users = testTable("users", db)
        val roles = testTable("roles", db)

        db.transaction {
            val scoped = provider.current()
            users.insert(TestEntity(1, "user"))
            roles.insert(TestEntity(2, "role"))
            assertNotSame(root, scoped)
            assertSame(scoped, provider.current())
        }

        assertEquals(0, root.executedSql.size)
        assertEquals(2, transaction.executedSql.size)
        assertEquals(1, runner.commits)
        assertEquals(0, runner.rollbacks)
        assertSame(root, provider.current())
    }

    @Test
    fun nestedTransactionJoinsOuterSession() = runTest {
        val root = RecordingSession("root")
        val transaction = RecordingSession("transaction")
        val runner = RecordingTransactionRunner(transaction)
        val provider = CoroutineDbSessionProvider(root, runner)

        provider.transaction {
            val outer = provider.current()
            provider.transaction {
                assertSame(outer, provider.current())
            }
        }

        assertEquals(1, runner.starts)
        assertEquals(1, runner.commits)
    }

    @Test
    fun exceptionRollsBackAndRestoresRootSession() = runTest {
        val root = RecordingSession("root")
        val runner = RecordingTransactionRunner(RecordingSession("transaction"))
        val provider = CoroutineDbSessionProvider(root, runner)

        assertFailsWith<IllegalStateException> {
            provider.transaction { error("boom") }
        }

        assertEquals(0, runner.commits)
        assertEquals(1, runner.rollbacks)
        assertSame(root, provider.current())
    }

    @Test
    fun cancellationRollsBackAndPropagates() = runTest {
        val runner = RecordingTransactionRunner(RecordingSession("transaction"))
        val provider = CoroutineDbSessionProvider(RecordingSession("root"), runner)

        assertFailsWith<CancellationException> {
            provider.transaction { throw CancellationException("cancelled") }
        }

        assertEquals(0, runner.commits)
        assertEquals(1, runner.rollbacks)
    }

    @Test
    fun transactionSessionCannotExecuteAfterCompletion() = runTest {
        val provider = CoroutineDbSessionProvider(
            RecordingSession("root"),
            RecordingTransactionRunner(RecordingSession("transaction")),
        )
        lateinit var captured: DbSession

        provider.transaction { captured = provider.current() }

        assertFailsWith<IllegalStateException> {
            captured.execute("UPDATE users SET name = 'late'")
        }
    }

    @Test
    fun tableExecutionNotifiesInterceptorExactlyOnce() = runTest {
        val session = RecordingSession("root")
        val provider = CoroutineDbSessionProvider(session, RecordingTransactionRunner(session))
        val interceptor = RecordingInterceptor()
        val db = SqlxDbContext(provider, listOf(interceptor))

        testTable("users", db).insert(TestEntity(1, "user"))

        assertEquals(1, interceptor.executions)
        assertEquals(0, interceptor.errors)
    }

    @Test
    fun entityQueryRewritesAndNotifiesInterceptorExactlyOnce() = runTest {
        val session = RecordingSession("root")
        val provider = CoroutineDbSessionProvider(session, RecordingTransactionRunner(session))
        val interceptor = RecordingInterceptor()
        val db = SqlxDbContext(provider, listOf(interceptor))

        testTable("users", db).query {}.list()

        assertEquals(1, interceptor.queryRewrites)
        assertEquals(1, interceptor.executions)
        assertEquals(0, interceptor.errors)
    }
}

private data class TestEntity(val id: Long, val name: String)

private fun testTable(name: String, db: SqlxDbContext) = SqlxTableAdapter<TestEntity, Long>(
    meta = object : EntityMeta<TestEntity> {
        override val table = name
        override val idColumn = "id"
        override val columns = listOf("id", "name")
    },
    dbProvider = { db },
    mapper = EntityMapper { error("mapping is not used by this test") },
    toParams = { mapOf("id" to it.id, "name" to it.name) },
    autoGenerateId = false,
)

private class RecordingSession(
    val name: String,
    override val dialect: Dialect = PostgresDialect,
) : DbSession {
    val executedSql = mutableListOf<String>()

    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> = emptyList()

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        executedSql += sql
        return 1
    }
}

private class RecordingTransactionRunner(
    private val session: DbSession,
) : DbTransactionRunner {
    var starts = 0
    var commits = 0
    var rollbacks = 0

    override suspend fun <R> run(block: suspend (DbSession) -> R): R {
        starts++
        return try {
            block(session).also { commits++ }
        } catch (error: Throwable) {
            rollbacks++
            throw error
        }
    }
}

private class RecordingInterceptor : QueryInterceptor {
    var queryRewrites = 0
    var executions = 0
    var errors = 0

    override fun beforeQuery(ast: QueryAst<*>): QueryAst<*> {
        queryRewrites++
        return ast
    }

    override fun onExecute(sql: String, args: List<Any?>, elapsedMs: Long) {
        executions++
    }

    override fun onError(sql: String, args: List<Any?>, error: Throwable) {
        errors++
    }
}
