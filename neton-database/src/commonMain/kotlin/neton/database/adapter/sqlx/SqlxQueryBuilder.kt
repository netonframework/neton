package neton.database.adapter.sqlx

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import neton.database.api.*

/**
 * SqlxTable 的 QueryBuilder 实现
 * 基于 sqlx4k findAll 后在内存过滤；可扩展为 SQL 生成
 */
class SqlxQueryBuilder<T : Any>(
    private val fetchAll: suspend () -> List<T>
) : QueryBuilder<T> {

    private val predicates = mutableListOf<(T) -> Boolean>()
    private var limitCount: Int? = null
    private var offsetCount: Int = 0
    private var orderByComparator: Comparator<T>? = null

    override fun where(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T> {
        val ctx = TypedQueryContext<T>()
        val cond = ctx.block()
        predicates.add(when (cond) {
            is LambdaQueryCondition<*> -> @Suppress("UNCHECKED_CAST") (cond as LambdaQueryCondition<T>).predicate
            else -> { _: T -> true }
        })
        return this
    }

    override fun and(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T> = where(block)
    override fun or(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T> = where(block)

    override fun <R : Any> join(
        targetEntity: KClass<R>,
        type: JoinType,
        block: QueryContext<R>.() -> QueryCondition
    ): QueryBuilder<T> = this

    override fun with(vararg relations: KClass<*>): QueryBuilder<T> = this

    override fun <V> orderBy(orderBy: OrderBy<T, V>): QueryBuilder<T> {
        val typed = orderBy as? TypedOrderBy<T, V>
        orderByComparator = typed?.comparator
        return this
    }

    override fun orderBy(vararg orderBys: OrderBy<T, *>): QueryBuilder<T> {
        orderByComparator = orderBys.mapNotNull { (it as? TypedOrderBy<T, *>)?.comparator }
            .reduceOrNull { a, b -> a.then(b) }
        return this
    }

    override fun limit(count: Int): QueryBuilder<T> {
        limitCount = count
        return this
    }

    override fun offset(count: Int): QueryBuilder<T> {
        offsetCount = count
        return this
    }

    override fun <V> groupBy(field: FieldRef<T, V>): QueryBuilder<T> = this
    override fun having(block: QueryContext<T>.() -> QueryCondition): QueryBuilder<T> = this
    override fun distinct(): QueryBuilder<T> = this

    private suspend fun applyFilters(): List<T> {
        var result = fetchAll()
        for (pred in predicates) {
            result = result.filter(pred)
        }
        orderByComparator?.let { result = result.sortedWith(it) }
        result = result.drop(offsetCount)
        limitCount?.let { result = result.take(it) }
        return result
    }

    override suspend fun fetch(): List<T> = applyFilters()
    override suspend fun fetchFirst(): T? = applyFilters().firstOrNull()
    override suspend fun fetchSingle(): T? {
        val list = applyFilters()
        return when (list.size) {
            0 -> null
            1 -> list.first()
            else -> throw IllegalStateException("Expected single result, got ${list.size}")
        }
    }

    override suspend fun paginate(page: Int, pageSize: Int): Page<T> {
        val total = count()
        val offset = (page - 1) * pageSize
        val items = offset(offset).limit(pageSize).fetch()
        return Page.of(items, total, page, pageSize)
    }

    override suspend fun count(): Long = applyFilters().size.toLong()
    override suspend fun exists(): Boolean = fetchFirst() != null
    override suspend fun delete(): Int = 0 // 批量删除需 SQL 支持
}

/** 可求值为 (T) -> Boolean 的 QueryCondition */
class LambdaQueryCondition<T>(val predicate: (T) -> Boolean) : QueryCondition {
    override infix fun and(other: QueryCondition): QueryCondition {
        val o = other as? LambdaQueryCondition<T> ?: return this
        return LambdaQueryCondition<T> { predicate(it) && o.predicate(it) }
    }
    override infix fun or(other: QueryCondition): QueryCondition {
        val o = other as? LambdaQueryCondition<T> ?: return this
        return LambdaQueryCondition<T> { predicate(it) || o.predicate(it) }
    }
}

/** Typed OrderBy，持有 Comparator */
class TypedOrderBy<T : Any, V>(val comparator: Comparator<T>) : OrderBy<T, V>

/** Typed QueryContext，KProperty1 -> Lambda 条件 */
class TypedQueryContext<T : Any> : QueryContext<T> {
    override fun <V> field(property: KProperty1<T, V>): FieldRef<T, V> = TypedFieldRef(property)
}

/** Typed FieldRef，构建 LambdaQueryCondition */
class TypedFieldRef<T : Any, V>(private val property: KProperty1<T, V>) : FieldRef<T, V> {
    private fun pred(f: (V?) -> Boolean): QueryCondition = LambdaQueryCondition<T> { entity -> f(property.get(entity)) }

    override infix fun eq(value: V): QueryCondition = pred { it == value }
    override infix fun ne(value: V): QueryCondition = pred { it != value }
    override infix fun gt(value: V): QueryCondition = pred { v -> (v as? Comparable<V>)?.compareTo(value) == 1 ?: false }
    override infix fun gte(value: V): QueryCondition = pred { v -> (v as? Comparable<V>)?.compareTo(value) != -1 ?: false }
    override infix fun lt(value: V): QueryCondition = pred { v -> (v as? Comparable<V>)?.compareTo(value) == -1 ?: false }
    override infix fun lte(value: V): QueryCondition = pred { v -> (v as? Comparable<V>)?.compareTo(value) != 1 ?: false }
    override infix fun like(pattern: String): QueryCondition = pred { (it as? String)?.contains(pattern) ?: false }
    override infix fun `in`(values: Collection<V>): QueryCondition = pred { it in values }
    override fun isNull(): QueryCondition = pred { it == null }
    override fun isNotNull(): QueryCondition = pred { it != null }
    override fun asc(): OrderBy<T, V> = TypedOrderBy<T, V>(Comparator { a, b ->
        @Suppress("UNCHECKED_CAST")
        (property.get(a) as? Comparable<Any>)?.compareTo(property.get(b) as Any) ?: 0
    })
    override fun desc(): OrderBy<T, V> = TypedOrderBy<T, V>(Comparator { a, b ->
        @Suppress("UNCHECKED_CAST")
        (property.get(b) as? Comparable<Any>)?.compareTo(property.get(a) as Any) ?: 0
    })
}
