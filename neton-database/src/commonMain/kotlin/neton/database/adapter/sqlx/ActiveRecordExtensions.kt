package neton.database.adapter.sqlx

import neton.database.api.Table

/**
 * ActiveRecord 风格扩展函数（语法糖）
 * 零侵入，不强制继承 Entity
 */
suspend fun <T : Any, ID : Any> T.save(table: Table<T, ID>): T = table.save(this)
suspend fun <T : Any, ID : Any> T.delete(table: Table<T, ID>): Boolean = table.delete(this)
