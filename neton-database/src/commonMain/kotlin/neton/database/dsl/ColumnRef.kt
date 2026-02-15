package neton.database.dsl

/**
 * 列引用，由 KSP 生成（如 UserMeta.id）。
 * Phase 1 仅持列名，无反射。
 */
data class ColumnRef(val name: String)
