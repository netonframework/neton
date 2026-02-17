package neton.database.dsl

import kotlin.reflect.KProperty1

/**
 * 列引用，由 KSP 生成（如 UserMeta.id）。
 * Phase 1 仅持列名，无反射。
 */
data class ColumnRef(val name: String)

/**
 * 将 Kotlin 属性引用转换为 ColumnRef（internal）。
 * 使用 camelCase → snake_case 转换（与 KSP 生成一致）。
 * 用户层通过 KProperty1 操作符（eq/like/gt 等）间接使用。
 */
internal fun KProperty1<*, *>.toColumnRef(): ColumnRef =
    ColumnRef(name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase())
