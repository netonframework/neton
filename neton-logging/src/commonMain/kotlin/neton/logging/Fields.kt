package neton.logging

/**
 * 结构化日志字段（v1 冻结）。
 * 业务数据放在 Fields 中，不拼进 msg；是一等公民。
 *
 * value 只允许：String、Number、Boolean、Enum、List/Map（递归同规则）；
 * 禁止任意业务对象（避免大对象/循环引用、保证可序列化与聚合）。
 */
typealias Fields = Map<String, Any?>

/**
 * 空字段，用于 [Logger] 默认参数。
 */
fun emptyFields(): Fields = emptyMap()
