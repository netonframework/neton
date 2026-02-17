package neton.database.api

/**
 * 审计时间戳自动填充配置（DB 列名）。
 * v1 冻结：框架只内建时间戳审计（@CreatedAt / @UpdatedAt），不内建用户审计。
 */
data class AutoFillConfig(
    val createdAtColumn: String? = null,
    val updatedAtColumn: String? = null
)
