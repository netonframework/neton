package neton.database.dsl

/**
 * v1 排序（Phase 4 JOIN 场景专用）。
 * 与 Phase 1 的 Ordering(ColumnRef, Dir) 不同，v1 直接存储 (tableAlias, column) 结构化数据。
 *
 * Phase 1 Ordering 保留用于单表查询（Table.query），v1 ColumnOrdering 用于 JOIN 查询（SelectBuilder）。
 */
data class ColumnOrdering(val tableAlias: String, val column: String, val dir: Dir)
