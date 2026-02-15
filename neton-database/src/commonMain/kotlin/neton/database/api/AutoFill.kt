package neton.database.api

/**
 * Phase 1 自动填充：提供「当前时间」与「当前用户 ID」注入点。
 * 由应用在启动时绑定（如从 NetonContext / Identity 取）。
 */
interface AutoFillProvider {
    fun nowMillis(): Long
    fun currentUserId(): Long?
}

/**
 * 自动填充列名（DB 列名，如 created_at）。
 */
data class AutoFillConfig(
    val createdAtColumn: String = "created_at",
    val updatedAtColumn: String = "updated_at",
    val createdByColumn: String = "created_by",
    val updatedByColumn: String = "updated_by"
)
