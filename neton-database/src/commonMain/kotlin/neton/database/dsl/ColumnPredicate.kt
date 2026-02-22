package neton.database.dsl

/**
 * v1 谓词 AST（Phase 2）。
 * 存储 (tableAlias, columnName, value) 三元组，不持有 Column 对象引用。
 * SQL 生成完全由 SqlBuilder + Dialect 负责。
 *
 * 类型安全由运算符层保证（ColRef<T,V>.eq(V)），
 * AST 层存储 Any? 是因为 sealed interface 的 data class 无法持有泛型。
 */
sealed interface ColumnPredicate {
    data class Eq(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Ne(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Gt(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Ge(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Lt(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Le(val tableAlias: String, val column: String, val value: Any?) : ColumnPredicate
    data class Like(val tableAlias: String, val column: String, val value: String) : ColumnPredicate
    data class In(val tableAlias: String, val column: String, val values: List<Any?>) : ColumnPredicate
    data class IsNull(val tableAlias: String, val column: String) : ColumnPredicate
    data class IsNotNull(val tableAlias: String, val column: String) : ColumnPredicate
    data class Between(val tableAlias: String, val column: String, val low: Any?, val high: Any?) : ColumnPredicate
    data class And(val children: List<ColumnPredicate>) : ColumnPredicate
    data class Or(val children: List<ColumnPredicate>) : ColumnPredicate
    data object True : ColumnPredicate
}

infix fun ColumnPredicate.and(other: ColumnPredicate): ColumnPredicate =
    ColumnPredicate.And(listOf(this, other))

infix fun ColumnPredicate.or(other: ColumnPredicate): ColumnPredicate =
    ColumnPredicate.Or(listOf(this, other))
