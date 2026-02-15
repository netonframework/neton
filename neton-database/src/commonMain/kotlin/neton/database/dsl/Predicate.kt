package neton.database.dsl

/**
 * Phase 1 查询条件 AST，仅描述条件，不生成 SQL。
 * SqlBuilder 负责 Predicate → SQL。
 */
sealed interface Predicate {
    data object True : Predicate
    data class And(val children: List<Predicate>) : Predicate
    data class Or(val children: List<Predicate>) : Predicate
    data class Eq(val column: ColumnRef, val value: Any?) : Predicate
    data class Like(val column: ColumnRef, val value: String) : Predicate
    data class In(val column: ColumnRef, val values: List<Any?>) : Predicate
    data class Gt(val column: ColumnRef, val value: Any?) : Predicate
    data class Ge(val column: ColumnRef, val value: Any?) : Predicate
    data class Lt(val column: ColumnRef, val value: Any?) : Predicate
    data class Le(val column: ColumnRef, val value: Any?) : Predicate
}

// 操作符（Neton 风格）
infix fun ColumnRef.eq(v: Any?): Predicate = Predicate.Eq(this, v)
infix fun ColumnRef.like(v: String): Predicate = Predicate.Like(this, v)
infix fun ColumnRef.`in`(vs: Collection<Any?>): Predicate = Predicate.In(this, vs.toList())
infix fun ColumnRef.gt(v: Any?): Predicate = Predicate.Gt(this, v)
infix fun ColumnRef.ge(v: Any?): Predicate = Predicate.Ge(this, v)
infix fun ColumnRef.lt(v: Any?): Predicate = Predicate.Lt(this, v)
infix fun ColumnRef.le(v: Any?): Predicate = Predicate.Le(this, v)

/** 将可选条件与另一条件用 AND 组合，供 normalizeForSoftDelete 等使用 */
fun and(left: Predicate?, right: Predicate): Predicate {
    if (right is Predicate.True) return left ?: Predicate.True
    if (left == null || left is Predicate.True) return right
    return Predicate.And(listOf(left, right))
}
