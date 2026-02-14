package neton.database.api

import kotlin.reflect.KProperty1

/**
 * 批量更新 DSL（v2）
 * User.where { User::status eq 0 }.update { set(User::status, 1) }
 */
interface UpdateScope<T : Any> {
    fun <V> set(prop: KProperty1<T, V>, value: V)
}
