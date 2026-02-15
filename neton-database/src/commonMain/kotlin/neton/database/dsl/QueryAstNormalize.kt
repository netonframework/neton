package neton.database.dsl

/**
 * 在 buildSelect / buildCount 前对 AST 做 SoftDelete 注入。
 * 若 includeDeleted == true 或 deletedColumn == null，返回原 AST；
 * 否则将 where 置为 (原 where) AND (deletedColumn = false)。
 */
fun <T : Any> QueryAst<T>.normalizeForSoftDelete(deletedColumn: ColumnRef?): QueryAst<T> {
    if (includeDeleted || deletedColumn == null) return this
    val deletedPredicate = deletedColumn eq false
    return copy(where = and(where, deletedPredicate))
}
