package neton.core.http

import kotlinx.serialization.Serializable

/**
 * 统一错误响应体（规范 v1.0.2）
 */
@Serializable
data class ErrorResponse(
    val success: Boolean = false,
    val message: String,
    val errors: List<ValidationError> = emptyList()
)
