package neton.database.dsl

enum class JoinType { INNER, LEFT, RIGHT, FULL }

data class JoinClause(
    val type: JoinType,               // INNER / LEFT / RIGHT / FULL
    val targetTableName: String,
    val targetAlias: String,
    val on: JoinCondition
)
