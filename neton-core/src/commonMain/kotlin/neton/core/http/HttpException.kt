package neton.core.http

/**
 * HTTP 异常基类 - 统一收口，路由层捕获后按 status 返回 ErrorResponse
 */
open class HttpException(
    val status: HttpStatus,
    override val message: String,
    val errors: List<ValidationError> = emptyList()
) : RuntimeException(message)

/**
 * 400 Bad Request
 */
open class BadRequestException(
    message: String = "Bad Request",
    errors: List<ValidationError> = emptyList()
) : HttpException(HttpStatus.BAD_REQUEST, message, errors)
