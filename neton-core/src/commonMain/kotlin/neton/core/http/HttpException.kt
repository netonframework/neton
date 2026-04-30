package neton.core.http

/**
 * HTTP 异常基类 - 统一收口，路由层捕获后按 status 返回 ErrorResponse
 *
 * `protocolCode` 是写入响应 envelope `body.code` 字段的数字（spec ERROR_CODE_SPEC）。
 * 默认从 [HttpStatus.protocolCode] 派生（粗粒度映射）；业务侧需要更细错误码时显式传入：
 *
 * ```kotlin
 * throw HttpException(HttpStatus.BAD_REQUEST, "phone format invalid", protocolCode = 10104)
 * ```
 */
open class HttpException(
    val status: HttpStatus,
    override val message: String,
    val errors: List<ValidationError> = emptyList(),
    val protocolCode: Int = status.protocolCode,
) : RuntimeException(message)

/**
 * 400 Bad Request
 */
open class BadRequestException(
    message: String = "Bad Request",
    errors: List<ValidationError> = emptyList()
) : HttpException(HttpStatus.BAD_REQUEST, message, errors)
