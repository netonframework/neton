package neton.database.dsl

/** JOIN ON 条件：左表.列 = 右表.列（纯结构化数据）*/
data class JoinCondition(
    val leftAlias: String,
    val leftColumn: String,
    val rightAlias: String,
    val rightColumn: String
)
