package neton.database.adapter.sqlx

import neton.database.api.EntityMapper
import neton.database.api.EntityQuery
import neton.database.api.DbContext
import neton.database.api.Page
import neton.database.api.ProjectionQuery
import neton.database.api.Row
import neton.database.api.SoftDeleteConfig
import neton.database.dsl.ColumnRef
import neton.database.dsl.QueryAst
import neton.database.dsl.QueryMeta
import neton.database.dsl.TableMeta
import neton.database.dsl.normalizeForSoftDelete
import neton.database.sql.BuiltSql
import neton.database.sql.SqlBuilder

/**
 * Phase 1 EntityQuery：持有 QueryAst，用 SqlBuilder 生成 SQL，sqlx 执行。
 */
internal class SqlxEntityQuery<T : Any>(
    private val adapter: SqlxTableAdapter<T, *>,
    private val ast: QueryAst<T>,
    private val softDeleteConfig: SoftDeleteConfig?
) : EntityQuery<T> {

    override suspend fun list(): List<T> {
        val normalized = adapter.rewriteAny(ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        return adapter.executePhase1Select(built, adapter.phase1Mapper())
    }

    override suspend fun count(): Long {
        val normalized = adapter.rewriteAny(ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildCount(normalized)
        return adapter.executePhase1Count(built)
    }

    override suspend fun page(page: Int, size: Int): Page<T> {
        val total = count()
        val normalized = adapter.rewriteAny(ast.normalizeForSoftDelete(softDeleteConfig))
            .copy(limit = size, offset = (page - 1).coerceAtLeast(0) * size)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        val items = adapter.executePhase1Select(built, adapter.phase1Mapper())
        return Page.of(items, total, page, size)
    }

    override suspend fun delete(): Long {
        val normalized = adapter.rewrite(ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildDelete(normalized)
        return adapter.executePhase1Mutate(built)
    }

    override suspend fun update(block: neton.database.api.UpdateScope<T>.() -> Unit): Long {
        val scope = SqlxUpdateScope<T>(adapter::propToColumn)
        scope.block()
        if (scope.assignments.isEmpty()) return 0L
        val normalized = adapter.rewrite(ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildUpdate(normalized, scope.assignments)
        return adapter.executePhase1Mutate(built)
    }

    override fun select(vararg columnNames: String): ProjectionQuery {
        val projection = columnNames.map { ColumnRef(it) }
        val newAst = ast.copy(projection = projection)
        return SqlxProjectionQuery(adapter, newAst, softDeleteConfig)
    }

    // Phase 3 typed select（TODO: 实现单表 typed projection）
    override fun <A> select(c1: kotlin.reflect.KProperty1<T, A>): neton.database.api.TypedProjection1<A> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>): neton.database.api.TypedProjection2<A, B> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>): neton.database.api.TypedProjection3<A, B, C> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C, D> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>, c4: kotlin.reflect.KProperty1<T, D>): neton.database.api.TypedProjection4<A, B, C, D> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C, D, E> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>, c4: kotlin.reflect.KProperty1<T, D>, c5: kotlin.reflect.KProperty1<T, E>): neton.database.api.TypedProjection5<A, B, C, D, E> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C, D, E, F> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>, c4: kotlin.reflect.KProperty1<T, D>, c5: kotlin.reflect.KProperty1<T, E>, c6: kotlin.reflect.KProperty1<T, F>): neton.database.api.TypedProjection6<A, B, C, D, E, F> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C, D, E, F, G> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>, c4: kotlin.reflect.KProperty1<T, D>, c5: kotlin.reflect.KProperty1<T, E>, c6: kotlin.reflect.KProperty1<T, F>, c7: kotlin.reflect.KProperty1<T, G>): neton.database.api.TypedProjection7<A, B, C, D, E, F, G> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }

    override fun <A, B, C, D, E, F, G, H> select(c1: kotlin.reflect.KProperty1<T, A>, c2: kotlin.reflect.KProperty1<T, B>, c3: kotlin.reflect.KProperty1<T, C>, c4: kotlin.reflect.KProperty1<T, D>, c5: kotlin.reflect.KProperty1<T, E>, c6: kotlin.reflect.KProperty1<T, F>, c7: kotlin.reflect.KProperty1<T, G>, c8: kotlin.reflect.KProperty1<T, H>): neton.database.api.TypedProjection8<A, B, C, D, E, F, G, H> {
        TODO("Phase 3: Single-table typed projection not yet implemented")
    }
}

/**
 * Phase 1 ProjectionQuery：SELECT 指定列，返回 List<Row>。
 */
internal class SqlxProjectionQuery(
    private val adapter: SqlxTableAdapter<*, *>,
    private val ast: QueryAst<*>,
    private val softDeleteConfig: SoftDeleteConfig?
) : ProjectionQuery {

    override suspend fun rows(): List<Row> {
        val normalized = rewriteQuery(adapter.db, ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        return adapter.executePhase1SelectRows(built)
    }

    override suspend fun count(): Long {
        val normalized = rewriteQuery(adapter.db, ast.normalizeForSoftDelete(softDeleteConfig))
        val built = adapter.phase1SqlBuilder().buildCount(normalized)
        return adapter.executePhase1Count(built)
    }

    override suspend fun page(page: Int, size: Int): Page<Row> {
        val total = count()
        val normalized = rewriteQuery(adapter.db, ast.normalizeForSoftDelete(softDeleteConfig))
            .copy(limit = size, offset = (page - 1).coerceAtLeast(0) * size)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        val items = adapter.executePhase1SelectRows(built)
        return Page.of(items, total, page, size)
    }
}

internal fun SqlxTableAdapter<*, *>.phase1Dialect(): neton.database.sql.Dialect =
    db.dialect

internal fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.phase1SqlBuilder(): SqlBuilder =
    SqlBuilder(phase1Dialect())

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> SqlxTableAdapter<T, *>.rewrite(ast: QueryAst<T>): QueryAst<T> =
    rewriteQuery(db, ast) as QueryAst<T>

internal fun SqlxTableAdapter<*, *>.rewriteAny(ast: QueryAst<*>): QueryAst<*> =
    rewriteQuery(db, ast)

private fun rewriteQuery(db: DbContext, ast: QueryAst<*>): QueryAst<*> =
    db.interceptors.fold(ast) { current, interceptor -> interceptor.beforeQuery(current) }

internal fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.phase1Mapper(): EntityMapper<T> = entityMapper

internal suspend fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.executePhase1Select(
    built: BuiltSql,
    rowMapper: EntityMapper<T>
): List<T> = db.query(built).map(rowMapper::map)

internal suspend fun SqlxTableAdapter<*, *>.executePhase1Count(built: BuiltSql): Long =
    db.query(built).firstOrNull()?.long("count") ?: 0L

internal suspend fun SqlxTableAdapter<*, *>.executePhase1SelectRows(built: BuiltSql): List<Row> = db.query(built)

internal suspend fun SqlxTableAdapter<*, *>.executePhase1Mutate(built: BuiltSql): Long = db.executeBuilt(built)

/** 收集 EntityQuery.update { set(...) } 的列赋值，列名通过 propToColumn 转换。 */
private class SqlxUpdateScope<T : Any>(
    private val propToColumn: (String) -> String
) : neton.database.api.UpdateScope<T> {
    val assignments = linkedMapOf<String, Any?>()
    override fun <V> set(prop: kotlin.reflect.KProperty1<T, V>, value: V) {
        assignments[propToColumn(prop.name)] = value
    }
}
