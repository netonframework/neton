package neton.database.dsl

/**
 * v1 查询 AST：支持多表 JOIN（public，原则 16）。
 * 所有字段都是纯结构化数据（字符串 + 枚举），不持有 Column/TableRef 对象引用。
 * SQL 生成完全由 SqlBuilder + Dialect 负责（SqlBuilder 为 internal）。
 *
 * public 暴露是为了未来扩展：
 * - QueryInterceptor.beforeExecute(ast)
 * - query cache（AST 可哈希）
 * - 多租户 rewrite（AST 可重写）
 * - 慢 SQL 分析
 */
data class SelectAst(
    val fromTable: String,
    val fromAlias: String,
    val joins: List<JoinClause> = emptyList(),
    val where: ColumnPredicate? = null,
    val orderBy: List<ColumnOrdering> = emptyList(),
    val projection: List<ProjectionExpr> = emptyList(),
    val groupBy: List<ProjectionExpr> = emptyList(),
    val having: ColumnPredicate? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val distinct: Boolean = false
)

/**
 * 投影表达式（预留 Phase 5 聚合扩展）。
 * Phase 4 只使用 Col；Phase 5 扩展 Agg。
 */
sealed interface ProjectionExpr {
    /** 普通列引用：alias + columnName */
    data class Col(val tableAlias: String, val columnName: String) : ProjectionExpr

    /**
     * 聚合/表达式（Phase 5 预留，Phase 4 不实现）：
     * 例如 count(*) as total, sum(amount) as total_amount
     */
    data class Agg(val expression: String, val outputAlias: String) : ProjectionExpr
}
