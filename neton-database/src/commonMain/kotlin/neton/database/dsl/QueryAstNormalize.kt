package neton.database.dsl

import neton.database.api.SoftDeleteConfig

/**
 * 在 buildSelect / buildCount 前对 AST 做 SoftDelete 注入。
 * 若 includeDeleted == true 或 config == null，返回原 AST；
 * 否则将 where 置为 (原 where) AND (deletedColumn = notDeletedValue)。
 */
fun <T : Any> QueryAst<T>.normalizeForSoftDelete(config: SoftDeleteConfig?): QueryAst<T> {
    if (includeDeleted || config == null) return this
    val deletedPredicate = ColumnRef(config.deletedColumn) eq config.notDeletedValue
    return copy(where = and(where, deletedPredicate))
}
