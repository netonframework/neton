package neton.database.api

import kotlin.reflect.KClass
import neton.core.http.NotFoundException

/**
 * 表级 CRUD 接口（单表操作，≈ MyBatis-Plus Mapper）
 *
 * @param T 实体类型
 * @param ID 主键类型（如 Long、String、UUID）
 */
interface Table<T : Any, ID : Any> {

    // ===== 基础 CRUD 操作 =====

    suspend fun get(id: ID): T?
    suspend fun findAll(): List<T>
    suspend fun save(entity: T): T
    suspend fun saveAll(entities: List<T>): List<T>
    suspend fun insert(entity: T): T
    suspend fun insertBatch(entities: List<T>): Int
    suspend fun updateBatch(entities: List<T>): Int
    suspend fun update(entity: T): Boolean
    suspend fun destroy(id: ID): Boolean
    /** Phase 1：批量删除（有 @SoftDelete 时走 UPDATE deleted = true），返回影响行数 */
    suspend fun destroyMany(ids: Collection<ID>): Int
    /** Phase 1：按 id 批量查询，等价于 query { where { id in ids } }.list()，ids 空则返回空列表 */
    suspend fun many(ids: Collection<ID>): List<T>
    /** Phase 1：条件查单条，等价于 query { where(block) }.list().firstOrNull() */
    suspend fun oneWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): T?
    /** Phase 1：条件是否存在，等价于 query { where(block) }.count() > 0 */
    suspend fun existsWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): Boolean
    suspend fun delete(entity: T): Boolean
    suspend fun count(): Long
    suspend fun exists(id: ID): Boolean

    // ===== 查询构建器 =====

    fun query(): QueryBuilder<T>
    /** Phase 1：query { } 块，返回 EntityQuery（list/count/page 与 SqlBuilder 同源） */
    fun query(block: neton.database.dsl.QueryScope<T>.() -> Unit): EntityQuery<T>
    suspend fun <R> withTransaction(block: suspend Table<T, ID>.() -> R): R
    suspend fun ensureTable() {}
}

suspend fun <T : Any, ID : Any> Table<T, ID>.getOrThrow(id: ID): T =
    get(id) ?: throw NotFoundException("Entity not found: id=$id")

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

/** Phase 1 冻结：4 字段 + 计算 totalPages，规范 §4.1 */
data class Page<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int
) {
    val totalPages: Int get() = if (size > 0) ((total + size - 1) / size).toInt() else 0

    companion object {
        fun <T> of(items: List<T>, total: Long, page: Int, size: Int): Page<T> =
            Page(items, total, page, size)
    }
}
