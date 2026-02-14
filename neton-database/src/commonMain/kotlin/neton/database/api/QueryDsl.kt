package neton.database.api

import kotlin.reflect.KProperty1
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 查询条件（DSL 内部使用，业务无需关心） */
data class QueryClause(
    val column: String,
    val op: String,
    val value: Any?
)

/** v2：条件表达式，可 and/or 组合 */
sealed class Predicate {
    data class Clause(val queryClause: QueryClause) : Predicate()
    data class And(val left: Predicate, val right: Predicate) : Predicate()
    data class Or(val left: Predicate, val right: Predicate) : Predicate()
    /** 无条件，用于 User.where { all() }.list() */
    data object True : Predicate()
}

/** 将 Predicate 展平为 AND 列表（Or 在 buildSelect 中单独处理） */
internal fun Predicate.toClausesList(): List<QueryClause> = when (this) {
    is Predicate.Clause -> listOf(queryClause)
    is Predicate.And -> left.toClausesList() + right.toClausesList()
    is Predicate.Or -> left.toClausesList() + right.toClausesList()
    is Predicate.True -> emptyList()
}

/** v2：与 PredicateScope 同义，DSL 条件容器 */
typealias PredicateScope<T> = QueryScope<T>

/** 组合条件 */
infix fun Predicate.and(other: Predicate): Predicate = Predicate.And(this, other)
infix fun Predicate.or(other: Predicate): Predicate = Predicate.Or(this, other)

/**
 * QueryScope - DSL 条件构建，block 返回 Predicate
 * 用法: User.where { User::status eq 1 } 或 User.where { (User::status eq 1) and (User::age gt 18) }
 */
class QueryScope<T : Any>(
    private val propToColumn: (String) -> String
) {
    private fun KProperty1<T, *>.col(): String = propToColumn(name)

    infix fun <V> KProperty1<T, V>.eq(value: V): Predicate =
        Predicate.Clause(QueryClause(col(), "=", value))
    infix fun <V : Comparable<V>> KProperty1<T, V>.gt(value: V): Predicate =
        Predicate.Clause(QueryClause(col(), ">", value))
    infix fun <V : Comparable<V>> KProperty1<T, V>.lt(value: V): Predicate =
        Predicate.Clause(QueryClause(col(), "<", value))
    infix fun KProperty1<T, String>.like(value: String): Predicate =
        Predicate.Clause(QueryClause(col(), "LIKE", value))
    infix fun <V : Comparable<V>> KProperty1<T, V>.between(range: Pair<V, V>): Predicate =
        Predicate.Clause(QueryClause(col(), "BETWEEN", range))
    /** 无条件，查全部：User.where { all() }.list() */
    fun all(): Predicate = Predicate.True
}

/**
 * Query - v2 惰性查询：where / orderBy / limit / offset / page / list / first / one / count / exists / flow / delete / update
 */
interface Query<T : Any> {
    fun orderBy(prop: KProperty1<T, *>, ascending: Boolean = true): Query<T>
    fun orderBy(order: Pair<KProperty1<T, *>, Boolean>): Query<T>
    fun orderBy(vararg orders: Order<T>): Query<T>
    fun limit(n: Int): Query<T>
    fun offset(n: Int): Query<T>
    fun page(page: Int, size: Int): Query<T>
    suspend fun list(): List<T>
    suspend fun first(): T?
    suspend fun firstOrNull(): T?
    suspend fun one(): T
    suspend fun oneOrNull(): T?
    suspend fun count(): Long
    suspend fun exists(): Boolean
    fun flow(): Flow<T>
    suspend fun delete(): Long
    suspend fun update(block: UpdateScope<T>.() -> Unit): Long
    /** 分页列表，返回 Page<T> */
    suspend fun listPage(): Page<T>
}
