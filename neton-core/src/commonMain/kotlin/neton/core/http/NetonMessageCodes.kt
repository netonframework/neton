package neton.core.http

/**
 * 框架自己下发给客户端的**机器码**（spec ERROR_CODE_SPEC「消息即机器码」）。
 *
 * 响应信封里的 `message` 永远是一个 `UPPER_SNAKE_CASE` 机器码，不是句子：客户端拿它（以及字段错误的
 * `code`）去查自己的语言包。数字 `code`（[NetonErrorCode]）决定错误类别与 HTTP status，`message`
 * 决定具体是哪一种。框架内建的取值都在这里；业务模块用自己的 `<Module>ErrorCodes` 目录。
 */
object NetonErrorMessage {
    /** 声明式校验或参数绑定失败；细节在 `errors[]`（字段 `code` 见 [ValidationCode]）。 */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    /** 参数无效（[BadRequestException] 的缺省值）。 */
    const val INVALID_PARAMS = "INVALID_PARAMS"
    /** 请求体不是合法 JSON，或与目标类型对不上。 */
    const val INVALID_JSON = "INVALID_JSON"
    /** Content-Type 不被接受（如 body 需要 `application/json`）。 */
    const val UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"
    /** 请求体超过服务端上限（HTTP 413）。 */
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    /** 客户端没在时限内发完请求体（HTTP 408）。 */
    const val REQUEST_TIMEOUT = "REQUEST_TIMEOUT"
    /** 没有匹配的路由（HTTP 404）。 */
    const val ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    /** 不支持的 HTTP 方法（HTTP 405）。 */
    const val METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    /** 资源不存在（[NotFoundException] 的缺省值）。 */
    const val RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    /** 需要登录（未带或带了无效凭证）。 */
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    /** 已登录但无权访问。 */
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    /** CORS 预检：服务端没开 CORS。 */
    const val CORS_NOT_ENABLED = "CORS_NOT_ENABLED"
    /** CORS 预检：缺 `Origin` 头。 */
    const val ORIGIN_REQUIRED = "ORIGIN_REQUIRED"
    /** CORS 预检：`Origin` 不在白名单。 */
    const val ORIGIN_NOT_ALLOWED = "ORIGIN_NOT_ALLOWED"
    /** `@RateLimit` 命中且注解没给自己的机器码。 */
    const val RATE_LIMITED = "RATE_LIMITED"
    /** `@Lock` 未拿到锁。 */
    const val LOCK_NOT_ACQUIRED = "LOCK_NOT_ACQUIRED"
    /** 未处理的服务端异常（HTTP 500）；细节只进日志。 */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    /** 服务暂不可用（停机中 / 并发已满，HTTP 503）。 */
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    /** 处理超时（HTTP 504）。 */
    const val TIMEOUT = "TIMEOUT"
}

/**
 * 字段级错误（`errors[].code`）的框架内建机器码。
 *
 * 声明式校验注解（`@NotBlank`、`@Size`…）没写 `message` 时用这里的码；写了就用作者给的码
 * （必须同样是机器码，KSP 编译期校验）。字段错误的 `message` 与 `code` 相同——客户端按 `code` 翻译，
 * 需要的约束值（`min`/`max`/`value`）放在 `errors[].params` 里。
 */
object ValidationCode {
    /** `@NotBlank` / `@NotNull`，以及必填的路径/查询/Cookie/上传参数缺失。 */
    const val REQUIRED = "REQUIRED"
    /** `@Size` 作用于字符串，长度 < `min`。params: `min`, `max`。 */
    const val TOO_SHORT = "TOO_SHORT"
    /** `@Size` 作用于字符串，长度 > `max`。params: `min`, `max`。 */
    const val TOO_LONG = "TOO_LONG"
    /** `@Size` 作用于集合/数组，元素数 < `min`。params: `min`, `max`。 */
    const val TOO_FEW_ITEMS = "TOO_FEW_ITEMS"
    /** `@Size` 作用于集合/数组，元素数 > `max`。params: `min`, `max`。 */
    const val TOO_MANY_ITEMS = "TOO_MANY_ITEMS"
    /** `@Min`：数值 < `min`。params: `min`。 */
    const val TOO_SMALL = "TOO_SMALL"
    /** `@Max`：数值 > `max`。params: `max`。 */
    const val TOO_LARGE = "TOO_LARGE"
    /** `@Pattern` 不匹配。 */
    const val INVALID_FORMAT = "INVALID_FORMAT"
    /** `@Email` 不是合法邮箱。 */
    const val INVALID_EMAIL = "INVALID_EMAIL"
    /** 请求体不是合法 JSON / 与目标类型对不上（path 为 `$`）。 */
    const val INVALID_JSON = "INVALID_JSON"
    /** 参数应为整数。 */
    const val INVALID_INTEGER = "INVALID_INTEGER"
    /** 参数应为数字。 */
    const val INVALID_NUMBER = "INVALID_NUMBER"
    /** 参数应为布尔（true/false/1/0/on/off）。 */
    const val INVALID_BOOLEAN = "INVALID_BOOLEAN"
    /** 参数取值无法解析为目标类型（其他类型）。 */
    const val INVALID_VALUE = "INVALID_VALUE"
}

/** 机器码的形状：`UPPER_SNAKE_CASE`，以字母开头。 */
val MACHINE_CODE_REGEX: Regex = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")

/** [value] 是否是合法机器码。 */
fun isMachineCode(value: String): Boolean = MACHINE_CODE_REGEX.matches(value)
