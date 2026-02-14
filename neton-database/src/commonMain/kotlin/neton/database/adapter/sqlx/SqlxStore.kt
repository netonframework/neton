package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.api.Predicate
import neton.database.api.Query
import neton.database.api.QueryBuilder
import neton.database.api.PredicateScope
import neton.database.api.Table

/**
 * Table 唯一样板实现（框架内部，由 KSP 生成 @Repository 代码使用）
 * 业务层不应直接引用此类
 */
abstract class SqlxStore<T : Any>(
    protected val db: QueryExecutor,
    protected val statements: EntityStatements,
    protected val mapper: RowMapper<T>,
    protected val toParams: (T) -> Map<String, Any?>,
    protected val getId: (T) -> Any?
) : Table<T> {

    private fun isNew(id: Any?): Boolean =
        id == null || (id is Number && id.toLong() == 0L)

    override suspend fun get(id: Any): T? =
        db.fetchAll(statements.selectById.bind("id", id), mapper).getOrThrow().firstOrNull()

    override suspend fun findAll(): List<T> =
        db.fetchAll(statements.selectAll, mapper).getOrThrow()

    override suspend fun count(): Long =
        findAll().size.toLong()

    override suspend fun exists(id: Any): Boolean = get(id) != null

    override suspend fun destroy(id: Any): Boolean =
        db.execute(statements.deleteById.bind("id", id)).getOrThrow() > 0

    override suspend fun updateById(id: Any, block: T.() -> T): T? =
        get(id)?.let { save(block(it)) }

    override suspend fun insert(entity: T): T {
        val params = toParams(entity).filterKeys { it != "id" }
        db.execute(bindAll(statements.insert, params)).getOrThrow()
        return entity
    }

    override suspend fun update(entity: T): Boolean =
        db.execute(bindAll(statements.update, toParams(entity))).getOrThrow() > 0

    override suspend fun insertBatch(entities: List<T>): Int {
        if (entities.isEmpty()) return 0
        return (db as io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional).transaction {
            var count = 0
            for (e in entities) {
                execute(bindAll(statements.insert, toParams(e).filterKeys { it != "id" })).getOrThrow()
                count++
            }
            count
        }
    }

    override suspend fun updateBatch(entities: List<T>): Int {
        if (entities.isEmpty()) return 0
        return (db as io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional).transaction {
            var count = 0
            for (e in entities) {
                if (execute(bindAll(statements.update, toParams(e))).getOrThrow() > 0) count++
            }
            count
        }
    }

    override suspend fun save(entity: T): T {
        val id = getId(entity)
        return if (isNew(id)) insert(entity) else {
            update(entity)
            entity
        }
    }

    override suspend fun saveAll(entities: List<T>): List<T> =
        (db as io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional).transaction {
            entities.map { e ->
                val id = getId(e)
                if (isNew(id)) execute(bindAll(statements.insert, toParams(e).filterKeys { it != "id" })).getOrThrow()
                else execute(bindAll(statements.update, toParams(e))).getOrThrow()
                e
            }
        }

    override suspend fun delete(entity: T): Boolean {
        val id = getId(entity) ?: return false
        return destroy(id)
    }

    override suspend fun <R> withTransaction(block: suspend Table<T>.() -> R): R =
        (db as io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional).transaction { this@SqlxStore.block() }

    override fun query(): QueryBuilder<T> =
        SqlxQueryBuilder { findAll() }

    override fun where(block: PredicateScope<T>.() -> Predicate): Query<T> =
        throw UnsupportedOperationException("where DSL requires SqlxStoreAdapter; use KSP v3 Store generation")

    /** KSP 生成 Repository 实现用：自定义 Statement 查单条 */
    suspend fun queryOne(statement: Statement, params: Map<String, Any?>): T? =
        db.fetchAll(bindAll(statement, params), mapper).getOrThrow().firstOrNull()

    /** KSP 生成 Repository 实现用：自定义 Statement 查列表 */
    suspend fun queryList(statement: Statement, params: Map<String, Any?>): List<T> =
        db.fetchAll(bindAll(statement, params), mapper).getOrThrow()

    /** KSP 生成 Repository 实现用：COUNT 等标量查询 */
    suspend fun queryScalar(statement: Statement, params: Map<String, Any?>): Long {
        val rows = db.fetchAll(bindAll(statement, params)).getOrThrow()
        return rows.firstOrNull()?.get(0)?.toString()?.toLongOrNull() ?: 0L
    }

    /** KSP 生成 Repository 实现用：DELETE 等执行 */
    suspend fun execute(statement: Statement, params: Map<String, Any?>): Long =
        db.execute(bindAll(statement, params)).getOrThrow()

    private fun bindAll(stmt: Statement, params: Map<String, Any?>): Statement =
        params.entries.fold(stmt) { s, (k, v) -> s.bind(k, v) }
}
