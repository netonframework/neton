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
enum class HttpStatus(val code: Int, val message: String) {
    // 2xx Success
    OK(200, "OK"),
    CREATED(201, "Created"),
    ACCEPTED(202, "Accepted"),
    NO_CONTENT(204, "No Content"),
    
    // 3xx Redirection
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),
    NOT_MODIFIED(304, "Not Modified"),
    
    // 4xx Client Error
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
    CONFLICT(409, "Conflict"),
    UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),
    
    // 5xx Server Error
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable");
    
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