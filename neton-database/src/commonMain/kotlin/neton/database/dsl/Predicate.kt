package neton.database.dsl

import kotlin.reflect.KProperty1

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

// ColumnRef 操作符（internal — 框架内部使用，用户层统一走 KProperty1）
internal infix fun ColumnRef.eq(v: Any?): Predicate = Predicate.Eq(this, v)
internal infix fun ColumnRef.like(v: String): Predicate = Predicate.Like(this, v)
internal infix fun ColumnRef.`in`(vs: Collection<Any?>): Predicate = Predicate.In(this, vs.toList())
internal infix fun ColumnRef.gt(v: Any?): Predicate = Predicate.Gt(this, v)
internal infix fun ColumnRef.ge(v: Any?): Predicate = Predicate.Ge(this, v)
internal infix fun ColumnRef.lt(v: Any?): Predicate = Predicate.Lt(this, v)
internal infix fun ColumnRef.le(v: Any?): Predicate = Predicate.Le(this, v)

// KProperty1 操作符 — 唯一对外 API，支持 SystemUser::username eq "jack" 风格
infix fun KProperty1<*, *>.eq(v: Any?): Predicate = toColumnRef().eq(v)
infix fun KProperty1<*, *>.like(v: String): Predicate = toColumnRef().like(v)
infix fun KProperty1<*, *>.`in`(vs: Collection<Any?>): Predicate = toColumnRef().`in`(vs)
infix fun KProperty1<*, *>.gt(v: Any?): Predicate = toColumnRef().gt(v)
infix fun KProperty1<*, *>.ge(v: Any?): Predicate = toColumnRef().ge(v)
infix fun KProperty1<*, *>.lt(v: Any?): Predicate = toColumnRef().lt(v)
infix fun KProperty1<*, *>.le(v: Any?): Predicate = toColumnRef().le(v)

/** 将可选条件与另一条件用 AND 组合，供 normalizeForSoftDelete 等使用 */
fun and(left: Predicate?, right: Predicate): Predicate {
    if (right is Predicate.True) return left ?: Predicate.True
    if (left == null || left is Predicate.True) return right
    return Predicate.And(listOf(left, right))
}
