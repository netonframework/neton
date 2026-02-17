package neton.database.dsl

import kotlin.reflect.KProperty1

data class Ordering(val column: ColumnRef, val dir: Dir)

enum class Dir { ASC, DESC }

// ColumnRef 排序（internal — 框架内部使用）
internal fun ColumnRef.asc(): Ordering = Ordering(this, Dir.ASC)
internal fun ColumnRef.desc(): Ordering = Ordering(this, Dir.DESC)

// KProperty1 排序 — 唯一对外 API，支持 SystemUser::id.desc() 风格
fun KProperty1<*, *>.asc(): Ordering = toColumnRef().asc()
fun KProperty1<*, *>.desc(): Ordering = toColumnRef().desc()
