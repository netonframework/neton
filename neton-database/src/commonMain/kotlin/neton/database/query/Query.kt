@file:Suppress("UNCHECKED_CAST")

package neton.database.query

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/* =========================================================
 * Core SPI（由 DatabaseComponent 内部实现，不对业务暴露）
 * ========================================================= */

interface QueryExecutor {
    suspend fun <T : Any> list(query: Query<T>): List<T>
    suspend fun <T : Any> first(query: Query<T>): T?
    fun <T : Any> flow(query: Query<T>): Flow<T>
    suspend fun <T : Any> count(query: Query<T>): Long
    suspend fun <T : Any> findById(entity: KClass<T>, id: Any): T?
}

/**
 * 运行时由 DatabaseComponent 注入
 */
object QueryRuntime {
    lateinit var executor: QueryExecutor
}

/* =========================================================
 * Query Model
 * ========================================================= */

data class Query<T : Any>(
    val entity: KClass<T>,
    val predicates: List<Predicate> = emptyList(),
    val orders: List<Order> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null
) {

    /* ---------- builder ---------- */

    fun where(block: WhereScope<T>.() -> Predicate): Query<T> =
        copy(predicates = predicates + WhereScope<T>().block())

    fun orderBy(vararg order: Order): Query<T> =
        copy(orders = orders + order)

    fun limit(limit: Int): Query<T> =
        copy(limit = limit)

    fun offset(offset: Int): Query<T> =
        copy(offset = offset)

    fun page(page: Int, size: Int): Query<T> =
        copy(limit = size, offset = (page - 1) * size)

    /* ---------- execution ---------- */

    suspend fun list(): List<T> =
        QueryRuntime.executor.list(this)

    suspend fun first(): T? =
        QueryRuntime.executor.first(this)

    suspend fun count(): Long =
        QueryRuntime.executor.count(this)

    fun flow(): Flow<T> =
        QueryRuntime.executor.flow(this)

    /** 分页执行，返回 PageResult；需先链式 .page(page, size) 或本方法传参 */
    suspend fun listPage(page: Int = 1, size: Int = 20): PageResult<T> {
        val total = count()
        val data = this.page(page, size).list()
        return PageResult(data, page, size, total)
    }
}

/* =========================================================
 * Predicate DSL
 * ========================================================= */

sealed interface Predicate

data class BinaryPredicate(
    val column: String,
    val op: Op,
    val value: Any?
) : Predicate

data class AndPredicate(val children: List<Predicate>) : Predicate
data class OrPredicate(val children: List<Predicate>) : Predicate

enum class Op {
    EQ, NE, GT, GE, LT, LE, LIKE, IN
}

/* =========================================================
 * Where DSL
 * ========================================================= */

class WhereScope<T : Any> {

    infix fun <R> KProperty1<T, R>.eq(v: R): Predicate =
        BinaryPredicate(name, Op.EQ, v)

    infix fun <R> KProperty1<T, R>.ne(v: R): Predicate =
        BinaryPredicate(name, Op.NE, v)

    infix fun <R : Comparable<R>> KProperty1<T, R>.gt(v: R): Predicate =
        BinaryPredicate(name, Op.GT, v)

    infix fun <R : Comparable<R>> KProperty1<T, R>.ge(v: R): Predicate =
        BinaryPredicate(name, Op.GE, v)

    infix fun <R : Comparable<R>> KProperty1<T, R>.lt(v: R): Predicate =
        BinaryPredicate(name, Op.LT, v)

    infix fun <R : Comparable<R>> KProperty1<T, R>.le(v: R): Predicate =
        BinaryPredicate(name, Op.LE, v)

    infix fun KProperty1<T, String>.like(v: String): Predicate =
        BinaryPredicate(name, Op.LIKE, v)

    infix fun <R> KProperty1<T, R>.inside(v: Collection<R>): Predicate =
        BinaryPredicate(name, Op.IN, v)

    infix fun Predicate.and(other: Predicate): Predicate =
        AndPredicate(listOf(this, other))

    infix fun Predicate.or(other: Predicate): Predicate =
        OrPredicate(listOf(this, other))
}

/* =========================================================
 * Order DSL
 * ========================================================= */

data class Order(
    val column: String,
    val asc: Boolean
)

fun <T : Any> KProperty1<T, *>.asc(): Order = Order(name, true)
fun <T : Any> KProperty1<T, *>.desc(): Order = Order(name, false)

/* =========================================================
 * Pagination
 * ========================================================= */

data class PageResult<T>(
    val data: List<T>,
    val page: Int,
    val size: Int,
    val total: Long
) {
    val totalPages: Int get() = if (size > 0) ((total + size - 1) / size).toInt() else 0
    val hasNext: Boolean get() = page < totalPages
    val hasPrev: Boolean get() = page > 1
}

/* =========================================================
 * ActiveRecord 风格扩展（业务层唯一需见的 API）
 * ========================================================= */

/** User::class.query() */
fun <T : Any> KClass<T>.query(): Query<T> =
    Query(this)

/**
 * User.where { User::status eq 1 }
 * 由 KSP 为每个 Entity 生成，形如：
 *   fun User.Companion.where(block: WhereScope<User>.() -> Predicate) = Query(User::class).where(block)
 */
// 泛型 Companion 扩展无法在 Kotlin 中单一定义，见 KSP 生成

/** User.get(id) 由 KSP 为每个 Entity 生成：suspend fun User.Companion.get(id: Any) = QueryRuntime.executor.findById(User::class, id) */

/** entity.save() */
suspend fun <T : Any> T.save(): T {
    EntityPersistence.save(this)
    return this
}

/* =========================================================
 * Persistence SPI（由 DatabaseComponent 实现）
 * ========================================================= */

object EntityPersistence {
    lateinit var saver: suspend (Any) -> Unit

    suspend fun save(entity: Any) = saver(entity)
}
