package neton.database.api

/**
 * Phase 1 软删配置：表有 @SoftDelete 时由 KSP 或手写传入 Adapter。
 * destroy(id) / destroyMany(ids) 走 UPDATE 而非 DELETE。
 */
data class SoftDeleteConfig(
    /** 列名，如 "deleted"，值为 true 表示已删 */
    val deletedColumn: String = "deleted",
    /**
     * 可选的时间戳列（如 "deleted_at"），软删时一并填 epoch millis；实体没有这个列就保持 null。
     *
     * 这里曾经默认 "deleted_at"，而唯一的生成方 KSP 从不传它 —— 于是 destroy() 恒拼出
     * SET deleted=..., deleted_at=...，对只有 deleted 列的表被驱动打回
     * [42703] column "deleted_at" of relation ... does not exist。软删配置必须描述实体，
     * 不能靠猜一个「通常并不存在」的列名。
     */
    val deletedAtColumn: String? = null,
    /** "未删除"的值。Int 字段用 0，Boolean 字段用 false */
    val notDeletedValue: Any = false
)
