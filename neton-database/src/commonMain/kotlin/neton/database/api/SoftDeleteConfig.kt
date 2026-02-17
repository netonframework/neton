package neton.database.api

/**
 * Phase 1 软删配置：表有 @SoftDelete 时由 KSP 或手写传入 Adapter。
 * destroy(id) / destroyMany(ids) 走 UPDATE 而非 DELETE。
 */
data class SoftDeleteConfig(
    /** 列名，如 "deleted"，值为 true 表示已删 */
    val deletedColumn: String = "deleted",
    /** 可选，如 "deleted_at"，软删时填 epoch millis */
    val deletedAtColumn: String? = "deleted_at",
    /** "未删除"的值。Int 字段用 0，Boolean 字段用 false */
    val notDeletedValue: Any = false
)
