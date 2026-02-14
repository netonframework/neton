package neton.core.http

import kotlinx.serialization.Serializable

/**
 * 控制器抛出此异常时，HTTP 适配器将响应 404 Not Found。
 */
class NotFoundException(message: String? = null) : HttpException(HttpStatus.NOT_FOUND, message ?: "Not Found")

/**
 * Content-Type 不支持时抛出，适配器响应 415 Unsupported Media Type
 */
class UnsupportedMediaTypeException(message: String = "Unsupported Media Type") : HttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message)

/**
 * 分布式锁未获取时抛出（如 neton-redis LockManager.withLock 拿不到锁）。
 * v1 固定映射 HTTP 409 Conflict。
 */
class LockNotAcquiredException(
    message: String = "Lock not acquired",
    val key: String
) : HttpException(HttpStatus.CONFLICT, message)

/**
 * 参数校验失败，适配器响应 400，body 使用 ValidationError 格式
 */
class ValidationException(
    errors: List<ValidationError>,
    message: String = "Validation failed"
) : BadRequestException(message, errors)

/** 字段级错误（HTTP/非 HTTP 通用，可序列化）；对外统一用 path */
@Serializable
data class ValidationError(
    val path: String,
    val message: String,
    val code: String? = null
)
