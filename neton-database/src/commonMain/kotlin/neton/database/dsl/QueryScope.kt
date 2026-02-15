package neton.database.dsl

/**
 * query { } 块内 DSL，收集 where / orderBy / select / limitOffset，产出 QueryAst。
 */
class QueryScope<T : Any>(private val meta: QueryMeta<T>) {
    private var where: Predicate? = null
    private val orderings = mutableListOf<Ordering>()
    private val projection = mutableListOf<ColumnRef>()
    private var includeDeleted = false
    private var limit: Int? = null
    private var offset: Int? = null

    fun where(block: PredicateScope.() -> Predicate) {
        val p = PredicateScope().block()
        where = if (p is Predicate.True) null else p
    }

    fun orderBy(vararg os: Ordering) {
        orderings += os
    }

    fun select(vararg cols: ColumnRef) {
        projection += cols
    }

    fun withDeleted() {
        includeDeleted = true
    }

    fun limitOffset(limit: Int, offset: Int) {
        this.limit = limit
        this.offset = offset
    }

    fun build(): QueryAst<T> = QueryAst(
        table = meta.tableMeta,
        where = where,
        orderBy = orderings.toList(),
        limit = limit,
        offset = offset,
        projection = projection.toList(),
        includeDeleted = includeDeleted
    )
}
