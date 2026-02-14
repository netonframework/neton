package neton.database.api

import kotlin.reflect.KProperty1

/**
 * 排序规则（v2 DSL）
 * User.where { }.orderBy(User::age.desc())
 */
data class Order<T : Any>(
    val property: KProperty1<T, *>,
    val asc: Boolean
)

fun <T : Any, V> KProperty1<T, V>.asc(): Order<T> = Order(this, true)
fun <T : Any, V> KProperty1<T, V>.desc(): Order<T> = Order(this, false)
