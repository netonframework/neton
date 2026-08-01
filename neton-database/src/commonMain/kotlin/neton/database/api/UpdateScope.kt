package neton.database.api

import kotlin.reflect.KProperty1

/**
 * 批量更新 DSL（v1）
 * User.where { User::status eq 0 }.update { set(User::status, 1) }
 */
interface UpdateScope<T : Any> {

    /** `col = ?`：用给定值覆盖列。 */
    fun <V> set(prop: KProperty1<T, V>, value: V)

    /**
     * `col = col + delta`：在数据库里原子增减，而不是「读出来、改、写回去」。
     *
     * 配合 `where { }` 就是一次 CAS：条件不成立时影响行数为 0，调用方据此判断失败，
     * 不需要额外加锁。典型用法——邀请码防超发：
     *
     * ```kotlin
     * val affected = InviteCodeTable.query {
     *     where { and(InviteCode::id eq id, InviteCode::usedCount lt maxUses) }
     * }.update { increment(InviteCode::usedCount) }
     * if (affected == 0L) throw BadRequestException("INVITE_CODE_EXHAUSTED")
     * ```
     *
     * 余额扣减同理：`where { balance gte amount }` + `decrement(balance, amount)`。
     *
     * @param delta 增量，可为负；[decrement] 是它的语义化包装
     */
    fun increment(prop: KProperty1<T, *>, delta: Long = 1L)

    /** `col = col - delta`。等价于 `increment(prop, -delta)`。 */
    fun decrement(prop: KProperty1<T, *>, delta: Long = 1L) = increment(prop, -delta)
}

/**
 * 一次 UPDATE 里对某列的赋值方式。
 *
 * 区分字面量与表达式，是为了让 `col = col + ?` 这类原子写在 SQL 层完成——
 * 读改写回的写法在并发下会丢更新。
 */
sealed interface ColumnAssignment {

    /** `col = ?` */
    data class Literal(val value: Any?) : ColumnAssignment

    /** `col = col + ?`（delta 可为负） */
    data class Delta(val delta: Long) : ColumnAssignment
}
