package neton.core.http

import kotlinx.serialization.json.JsonObject
import neton.core.component.NetonContext

/**
 * HttpContext 类型别名，人体工程学短名（规范 v1.0.1）
 */
typealias Ctx = HttpContext

/**
 * HTTP 上下文接口 - 核心抽象层
 * 提供统一的HTTP请求/响应访问接口，隔离底层HTTP服务器实现
 */
interface HttpContext {
    /**
     * 请求追踪ID - 用于日志系统和APM
     */
    val traceId: String
    
    /**
     * HTTP 请求对象
     */
    val request: HttpRequest
    
    /**
     * HTTP 响应对象
     */
    val response: HttpResponse
    
    /**
     * HTTP 会话对象
     */
    val session: HttpSession
    
    /**
     * 请求属性存储 - 用于在请求处理过程中传递数据
     */
    val attributes: MutableMap<String, Any>
    
    /**
     * 获取属性
     */
    fun getAttribute(name: String): Any? = attributes[name]
    
    /**
     * 设置属性
     */
    fun setAttribute(name: String, value: Any) {
        attributes[name] = value
    }
    
    /**
     * 移除属性
     */
    fun removeAttribute(name: String): Any? = attributes.remove(name)
    
    /**
     * 检查是否包含属性
     */
    fun hasAttribute(name: String): Boolean = attributes.containsKey(name)

    /**
     * 应用上下文（可选），用于在 handler 内获取 ValidatorRegistry 等组件。
     * 默认 null；Ktor 等适配器在构造 HttpContext 时注入。
     */
    fun getApplicationContext(): NetonContext? = null
}

/**
 * HTTP 状态码枚举
 */
/**
 * HTTP status + envelope `body.code` (per spec ERROR_CODE_SPEC §2 / SERVICE_RESPONSE_ENVELOPE_SPEC §3.1)。
 *
 * `protocolCode` 是写入响应信封 `code` 字段的数字，**不能**直接用 HTTP status——
 * spec 明确：HTTP status 是网关/接入层粗粒度，业务判定看 `body.code`，code 取自 `protocol::ErrorCode`。
 *
 * 业务侧抛 `HttpException(HttpStatus.X)` 时使用此默认映射；如需更细错误码（例如把
 * 400 区分为 `MissingRequiredParam=10101` / `InvalidFormat=10104`），由抛出方自行
 * 构造 `HttpException(status, message, protocolCode = ...)`（见 HttpException）。
 */
enum class HttpStatus(val code: Int, val message: String, val protocolCode: Int) {
    // 2xx Success → 0 Ok
    OK(200, "OK", 0),
    CREATED(201, "Created", 0),
    ACCEPTED(202, "Accepted", 0),
    NO_CONTENT(204, "No Content", 0),

    // 3xx Redirection（保持 0：不是错误）
    MOVED_PERMANENTLY(301, "Moved Permanently", 0),
    FOUND(302, "Found", 0),
    NOT_MODIFIED(304, "Not Modified", 0),

    // 4xx Client Error
    BAD_REQUEST(400, "Bad Request", 10100),                      // InvalidParams
    UNAUTHORIZED(401, "Unauthorized", 10000),                    // AuthRequired
    FORBIDDEN(403, "Forbidden", 10004),                          // PermissionDenied
    NOT_FOUND(404, "Not Found", 10201),                          // ResourceNotFound
    METHOD_NOT_ALLOWED(405, "Method Not Allowed", 10200),        // OperationNotAllowed
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type", 10102),// InvalidParamType
    CONFLICT(409, "Conflict", 10205),                            // OperationConflict
    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity", 10100),    // InvalidParams (semantic)
    TOO_MANY_REQUESTS(429, "Too Many Requests", 10300),          // RateLimitExceeded

    // 5xx Server Error
    INTERNAL_SERVER_ERROR(500, "Internal Server Error", 4),      // InternalError
    NOT_IMPLEMENTED(501, "Not Implemented", 1),                  // SystemError
    BAD_GATEWAY(502, "Bad Gateway", 9),                          // NetworkError
    SERVICE_UNAVAILABLE(503, "Service Unavailable", 3);          // ServiceUnavailable

    companion object {
        fun fromCode(code: Int): HttpStatus? = values().find { it.code == code }
    }
}

/**
 * HTTP 方法枚举
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE
}

/**
 * 请求头集合接口
 */
interface Headers {
    /**
     * 获取请求头值
     */
    operator fun get(name: String): String?
    
    /**
     * 获取所有同名请求头值
     */
    fun getAll(name: String): List<String>
    
    /**
     * 检查是否包含指定请求头
     */
    fun contains(name: String): Boolean
    
    /**
     * 获取所有请求头名称
     */
    fun names(): Set<String>
    
    /**
     * 转换为Map
     */
    fun toMap(): Map<String, List<String>>
}

/**
 * 可变请求头集合接口
 */
interface MutableHeaders : Headers {
    /**
     * 设置请求头值（覆盖已存在的值）
     */
    operator fun set(name: String, value: String)
    
    /**
     * 添加请求头值（不覆盖已存在的值）
     */
    fun add(name: String, value: String)
    
    /**
     * 移除请求头
     */
    fun remove(name: String)
    
    /**
     * 清空所有请求头
     */
    fun clear()
}

/**
 * 参数集合接口
 */
interface Parameters {
    /**
     * 获取参数值
     */
    operator fun get(name: String): String?
    
    /**
     * 获取所有同名参数值
     */
    fun getAll(name: String): List<String>
    
    /**
     * 检查是否包含指定参数
     */
    fun contains(name: String): Boolean
    
    /**
     * 获取所有参数名称
     */
    fun names(): Set<String>
    
    /**
     * 转换为Map
     */
    fun toMap(): Map<String, List<String>>
}

/**
 * Cookie 接口
 */
interface Cookie {
    val name: String
    val value: String
    val domain: String?
    val path: String?
    val maxAge: Int?
    val secure: Boolean
    val httpOnly: Boolean
    val sameSite: SameSite?
    
    enum class SameSite {
        STRICT, LAX, NONE
    }
}

/**
 * 可变 Cookie 接口
 */
interface MutableCookie : Cookie {
    override var value: String
    override var domain: String?
    override var path: String?
    override var maxAge: Int?
    override var secure: Boolean
    override var httpOnly: Boolean
    override var sameSite: Cookie.SameSite?
} 