package neton.database

import kotlinx.coroutines.test.runTest
import neton.database.adapter.sqlx.SqlxDbContext
import neton.database.adapter.sqlx.SqlxTableAdapter
import neton.database.api.CoroutineDbSessionProvider
import neton.database.api.DbSession
import neton.database.api.DbTransactionRunner
import neton.database.api.EntityMapper
import neton.database.api.EntityMeta
import neton.database.api.Row
import neton.database.sql.Dialect
import neton.database.sql.MySqlDialect
import neton.database.sql.PostgresDialect
import neton.database.sql.SqliteDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneratedIdInsertContractTest {
    @Test
    fun postgresInsertReturnsGeneratedEntity() = runTest {
        val session = InsertSession(PostgresDialect)
        val inserted = table(context(session), autoGenerateId = true).insert(InsertEntity(0, "Alice"))

        assertEquals(42L, inserted.id)
        assertEquals("Alice", inserted.name)
        assertTrue(session.queries.single().first.endsWith("RETURNING *"))
        assertEquals(0, session.executions.size)
    }

    @Test
    fun sqliteInsertReturnsGeneratedEntity() = runTest {
        val session = InsertSession(SqliteDialect)
        val inserted = table(context(session), autoGenerateId = true).insert(InsertEntity(0, "Alice"))

        assertEquals(42L, inserted.id)
        assertTrue(session.queries.single().first.endsWith("RETURNING *"))
    }

    @Test
    fun mysqlReadsGeneratedEntityInsideSameTransaction() = runTest {
        val session = InsertSession(MySqlDialect)
        val runner = InsertTransactionRunner(session)
        val db = SqlxDbContext(CoroutineDbSessionProvider(session, runner))

        val inserted = table(db, autoGenerateId = true).insert(InsertEntity(0, "Alice"))

        assertEquals(42L, inserted.id)
        assertEquals(1, runner.starts)
        assertEquals(1, session.executions.size)
        assertEquals(
            "SELECT * FROM users WHERE id = LAST_INSERT_ID()",
            session.queries.single().first,
        )
    }

    @Test
    fun explicitIdInsertDoesNotReadBackEntity() = runTest {
        val session = InsertSession(PostgresDialect)
        val original = InsertEntity(7, "Alice")

        val inserted = table(context(session), autoGenerateId = false).insert(original)

        assertSame(original, inserted)
        assertEquals(1, session.executions.size)
        assertTrue(session.queries.isEmpty())
    }
}

private data class InsertEntity(val id: Long, val name: String)

private fun context(session: DbSession): SqlxDbContext = SqlxDbContext(
    CoroutineDbSessionProvider(session, InsertTransactionRunner(session)),
)

private fun table(db: SqlxDbContext, autoGenerateId: Boolean) = SqlxTableAdapter<InsertEntity, Long>(
    meta = object : EntityMeta<InsertEntity> {
        override val table = "users"
        override val idColumn = "id"
        override val columns = listOf("id", "name")
    },
    dbProvider = { db },
    mapper = EntityMapper { row -> InsertEntity(row.long("id"), row.string("name")) },
    toParams = { mapOf("id" to it.id, "name" to it.name) },
    autoGenerateId = autoGenerateId,
)

private class InsertSession(
    override val dialect: Dialect,
) : DbSession {
    val queries = mutableListOf<Pair<String, Map<String, Any?>>>()
    val executions = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun query(sql: String, params: Map<String, Any?>): List<Row> {
        queries += sql to params
        return listOf(InsertRow(mapOf("id" to 42L, "name" to "Alice")))
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        executions += sql to params
        return 1
    }
}

private class InsertTransactionRunner(
    private val session: DbSession,
) : DbTransactionRunner {
    var starts = 0

    override suspend fun <R> run(block: suspend (DbSession) -> R): R {
        starts++
        return block(session)
    }
}

private class InsertRow(
    private val values: Map<String, Any?>,
) : Row {
    override fun long(name: String): Long = values[name] as Long
    override fun longOrNull(name: String): Long? = values[name] as? Long
    override fun int(name: String): Int = values[name] as Int
    override fun intOrNull(name: String): Int? = values[name] as? Int
    override fun string(name: String): String = values[name] as String
    override fun stringOrNull(name: String): String? = values[name] as? String
    override fun double(name: String): Double = values[name] as Double
    override fun doubleOrNull(name: String): Double? = values[name] as? Double
    override fun boolean(name: String): Boolean = values[name] as Boolean
    override fun booleanOrNull(name: String): Boolean? = values[name] as? Boolean
    override fun bytes(name: String): ByteArray = values[name] as ByteArray
    override fun bytesOrNull(name: String): ByteArray? = values[name] as? ByteArray
}
