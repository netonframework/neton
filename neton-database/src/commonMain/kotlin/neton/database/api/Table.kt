package neton.database.api

import kotlin.reflect.KClass
import neton.core.http.NotFoundException

/**
 * 表级 CRUD 接口（单表操作，≈ MyBatis-Plus Mapper）
 *
 * v2 冻结：id 为 Any；v2.1 建议改为 Table<T, ID : Any> 强类型
 */
interface Table<T : Any> {

    // ===== 基础 CRUD 操作 =====

    suspend fun get(id: Any): T?
    suspend fun findAll(): List<T>
    suspend fun save(entity: T): T
    suspend fun saveAll(entities: List<T>): List<T>
    suspend fun insert(entity: T): T
    suspend fun insertBatch(entities: List<T>): Int
    suspend fun updateBatch(entities: List<T>): Int
    suspend fun update(entity: T): Boolean
    suspend fun destroy(id: Any): Boolean
    suspend fun updateById(id: Any, block: T.() -> T): T?
    suspend fun delete(entity: T): Boolean
    suspend fun count(): Long
    suspend fun exists(id: Any): Boolean

    // ===== 查询构建器 =====

    fun query(): QueryBuilder<T>
    fun where(block: PredicateScope<T>.() -> Predicate): Query<T>
    suspend fun <R> withTransaction(block: suspend Table<T>.() -> R): R
    suspend fun ensureTable() {}
}

suspend fun <T : Any, ID : Any> Table<T>.getOrThrow(id: ID): T =
    get(id) ?: throw NotFoundException("Entity not found: id=$id (${id::class.simpleName ?: id::class})")

interface QueryBuilder<T : Any> {
    fun where(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T>
    fun and(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T>
    fun or(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T>
    fun <R : Any> join(targetEntity: KClass<R>, type: JoinType = JoinType.INNER, block: QueryContext<R>.() -> QueryCondition): QueryBuilder<T>
    fun with(vararg relations: KClass<*>): QueryBuilder<T>
    fun <V> orderBy(orderBy: OrderBy<T, V>): QueryBuilder<T>
    fun orderBy(vararg orderBys: OrderBy<T, *>): QueryBuilder<T>
    fun limit(count: Int): QueryBuilder<T>
    fun offset(count: Int): QueryBuilder<T>
    fun <V> groupBy(field: FieldRef<T, V>): QueryBuilder<T>
    fun having(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T>
    fun distinct(): QueryBuilder<T>
    suspend fun fetch(): List<T>
    suspend fun fetchFirst(): T?
    suspend fun fetchSingle(): T?
    suspend fun paginate(page: Int, pageSize: Int): Page<T>
    suspend fun count(): Long
    suspend fun exists(): Boolean
    suspend fun delete(): Int
}

interface QueryContext<T : Any> {
    fun <V> field(property: kotlin.reflect.KProperty1<T, V>): FieldRef<T, V>
}

interface FieldRef<T : Any, V> {
    infix fun eq(value: V): QueryCondition
    infix fun ne(value: V): QueryCondition
    infix fun gt(value: V): QueryCondition
    infix fun gte(value: V): QueryCondition
    infix fun lt(value: V): QueryCondition
    infix fun lte(value: V): QueryCondition
    infix fun like(pattern: String): QueryCondition
    infix fun `in`(values: Collection<V>): QueryCondition
    fun isNull(): QueryCondition
    fun isNotNull(): QueryCondition
    fun asc(): OrderBy<T, V>
    fun desc(): OrderBy<T, V>
}

interface QueryCondition {
    infix fun and(other: QueryCondition): QueryCondition
    infix fun or(other: QueryCondition): QueryCondition
}

interface OrderBy<T : Any, V>

enum class JoinType { INNER, LEFT, RIGHT, FULL }

data class Page<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
) {
    companion object {
        fun <T> of(items: List<T>, total: Long, page: Int, pageSize: Int): Page<T> {
            val totalPages = if (pageSize > 0) ((total + pageSize - 1) / pageSize).toInt() else 0
            return Page(items, total, page, pageSize, totalPages)
        }
    }
}
