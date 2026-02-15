package neton.database.dsl

/**
 * Phase 1 查询 AST，纯数据结构。
 * 由 QueryScope.build() 产出，交给 SqlBuilder 生成 SQL。
 */
data class QueryAst<T : Any>(
    val table: TableMeta,
    val where: Predicate? = null,
    val orderBy: List<Ordering> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val projection: List<ColumnRef> = emptyList(),
    val includeDeleted: Boolean = false
)
