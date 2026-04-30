package neton.core.http

/**
 * HTTP 异常基类 —— 业务侧抛出后由路由层捕获并写入响应信封。
 *
 * **`code` 是唯一权威**（取自 `protocol::ErrorCode` / spec ERROR_CODE_SPEC）；
 * HTTP status 由 framework 内部按 [httpStatusForErrorCode] 推导，业务侧不关心。
 *
 * 用法：
 *
 * ```kotlin
 * throw HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, "user $uid not found")
 * throw HttpException(MemberErrorCode.MOBILE_ALREADY_BOUND, "mobile already bound")
 * ```
 *
 * 旧的 `HttpException(HttpStatus.X, message)` 构造器已废弃；HttpStatus 不再作为业务错误的入口。
 */
open class HttpException(
    val code: Int,
    override val message: String,
    val errors: List<ValidationError> = emptyList(),
) : RuntimeException(message) {

    /**
     * 兼容旧签名 —— 临时桥接老代码。新代码请用 `HttpException(code: Int, message)`。
     *
     * 旧路径丢失了细错误码：`HttpStatus.BAD_REQUEST` 对应**多个** ErrorCode（10100/10101/10104），
     * 这里粗映射成段位起点（如 BAD_REQUEST → INVALID_PARAMS=10100）。需要更细错误码请改用主构造器。
     */
    @Deprecated(
        "使用 HttpException(code: Int, message) 主构造器；HttpStatus 不再作为业务错误的入口",
        ReplaceWith("HttpException(NetonErrorCode.INVALID_PARAMS, message, errors)")
    )
    constructor(
        status: HttpStatus,
        message: String,
        errors: List<ValidationError> = emptyList(),
    ) : this(legacyCodeFromStatus(status), message, errors)

    @Deprecated(
        "HttpStatus 由 framework 内部推导；业务侧不应再访问",
        ReplaceWith("code")
    )
    val status: HttpStatus
        get() = httpStatusForErrorCode(code)
}

/**
 * 兼容老代码 `HttpException(HttpStatus.X, msg)` 的粗映射。新代码请直接 throw with code。
 */
private fun legacyCodeFromStatus(status: HttpStatus): Int = when (status) {
    HttpStatus.OK, HttpStatus.CREATED, HttpStatus.ACCEPTED, HttpStatus.NO_CONTENT -> NetonErrorCode.OK
    HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY -> NetonErrorCode.INVALID_PARAMS
    HttpStatus.UNAUTHORIZED -> NetonErrorCode.AUTH_REQUIRED
    HttpStatus.FORBIDDEN -> NetonErrorCode.PERMISSION_DENIED
    HttpStatus.NOT_FOUND -> NetonErrorCode.RESOURCE_NOT_FOUND
    HttpStatus.METHOD_NOT_ALLOWED -> NetonErrorCode.OPERATION_NOT_ALLOWED
    HttpStatus.UNSUPPORTED_MEDIA_TYPE -> NetonErrorCode.INVALID_PARAM_TYPE
    HttpStatus.CONFLICT -> NetonErrorCode.OPERATION_CONFLICT
    HttpStatus.TOO_MANY_REQUESTS -> NetonErrorCode.RATE_LIMIT_EXCEEDED
    HttpStatus.SERVICE_UNAVAILABLE -> NetonErrorCode.SERVICE_UNAVAILABLE
    else -> NetonErrorCode.INTERNAL_ERROR
}

/**
 * 400 - 参数无效（默认 [NetonErrorCode.INVALID_PARAMS]）。需要更细错误码（如
 * `INVALID_FORMAT=10104` / `MISSING_REQUIRED_PARAM=10101`）请直接 throw `HttpException(code, msg)`。
 */
open class BadRequestException(
    message: String = "Bad Request",
    errors: List<ValidationError> = emptyList(),
) : HttpException(NetonErrorCode.INVALID_PARAMS, message, errors)
