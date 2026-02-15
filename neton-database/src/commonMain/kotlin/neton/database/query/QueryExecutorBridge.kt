package neton.database.query

import neton.database.api.QueryClause
import neton.database.api.Predicate as ApiPredicate
import neton.database.adapter.sqlx.SqlxTableAdapter
import neton.database.api.Table
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass

/**
 * 将 v2 query.Predicate 转为现有 api.Predicate（QueryClause 体系），供 SqlxTableAdapter 执行。
 */
internal fun neton.database.query.Predicate.toApiPredicate(propToColumn: (String) -> String): ApiPredicate = when (this) {
    is BinaryPredicate -> ApiPredicate.Clause(
        QueryClause(propToColumn(column), op.toSql(), value)
    )
    is AndPredicate -> when (children.size) {
        1 -> children.single().toApiPredicate(propToColumn)
        else -> children.map { it.toApiPredicate(propToColumn) }.reduce { a, b -> ApiPredicate.And(a, b) }
    }
    is OrPredicate -> when (children.size) {
        1 -> children.single().toApiPredicate(propToColumn)
        else -> children.map { it.toApiPredicate(propToColumn) }.reduce { a, b -> ApiPredicate.Or(a, b) }
    }
}

private fun Op.toSql(): String = when (this) {
    Op.EQ -> "="
    Op.NE -> "!="
    Op.GT -> ">"
    Op.GE -> ">="
    Op.LT -> "<"
    Op.LE -> "<="
    Op.LIKE -> "LIKE"
    Op.IN -> "IN"
}

/**
 * 由 DatabaseComponent 注入：根据 KClass 查找 Store，无则 null。
 * 注：DefaultQueryExecutor 需 SqlxTableAdapter（executeQuery 等），delegation 的 UserTable 不适用
 */
typealias TableRegistry = (KClass<*>) -> Table<*, *>?

/**
 * 使用 SqlxTableAdapter 实现 v2 QueryExecutor。
 * 当 registry 返回 delegation 的 Table（如 UserTable）时，无法执行，返回空。
 */
class DefaultQueryExecutor(private val tableRegistry: TableRegistry) : QueryExecutor {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> list(query: Query<T>): List<T> {
        val store = tableRegistry(query.entity) as? SqlxTableAdapter<T, *> ?: return emptyList()
        val predicate = when {
            query.predicates.isEmpty() -> ApiPredicate.True
            query.predicates.size == 1 -> query.predicates.single().toApiPredicate(store::propToColumn)
            else -> query.predicates.map { it.toApiPredicate(store::propToColumn) }.reduce { a, b -> ApiPredicate.And(a, b) }
        }
        val orderBy = query.orders.firstOrNull()?.let { store.propToColumn(it.column) to it.asc }
        return store.executeQuery(predicate, orderBy, query.limit, query.offset)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> first(query: Query<T>): T? {
        return list(query.copy(limit = 1)).firstOrNull()
    }

    override fun <T : Any> flow(query: Query<T>): Flow<T> = flow {
        list(query).forEach { emit(it) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> count(query: Query<T>): Long {
        val store = tableRegistry(query.entity) as? SqlxTableAdapter<T, *> ?: return 0L
        val predicate = when {
            query.predicates.isEmpty() -> ApiPredicate.True
            query.predicates.size == 1 -> query.predicates.single().toApiPredicate(store::propToColumn)
            else -> query.predicates.map { it.toApiPredicate(store::propToColumn) }.reduce { a, b -> ApiPredicate.And(a, b) }
        }
        return store.executeCount(predicate)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> findById(entity: KClass<T>, id: Any): T? {
        val store = tableRegistry(entity) as? SqlxTableAdapter<T, Long> ?: return null
        val idLong = when (id) {
            is Long -> id
            is Number -> id.toLong()
            else -> return null
        }
        return store.get(idLong)
    }
}
