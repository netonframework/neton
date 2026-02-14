package neton.security

/**
 * 请求上下文接口 - 为安全模块提供请求信息的抽象
 */
interface RequestContext {
    val path: String
    val method: String
    val headers: Map<String, String>
    val routeGroup: String?
    
    /**
     * 获取查询参数
     */
    fun getQueryParameter(name: String): String?
    
    /**
     * 获取所有查询参数
     */
    fun getQueryParameters(): Map<String, List<String>>
    
    /**
     * 获取请求体（如果有）
     */
    suspend fun getBodyAsString(): String?
    
    /**
     * 获取会话ID（如果有）
     */
    fun getSessionId(): String?
    
    /**
     * 获取远程客户端IP
     */
    fun getRemoteAddress(): String?
}

/**
 * 默认请求上下文实现
 */
class DefaultRequestContext(
    override val path: String,
    override val method: String,
    override val headers: Map<String, String>,
    override val routeGroup: String? = null,
    private val queryParameters: Map<String, List<String>> = mapOf(),
    private val body: String? = null,
    private val sessionId: String? = null,
    private val remoteAddress: String? = null
) : RequestContext {
    
    override fun getQueryParameter(name: String): String? =
        queryParameters[name]?.firstOrNull()
    
    override fun getQueryParameters(): Map<String, List<String>> = queryParameters
    
    override suspend fun getBodyAsString(): String? = body
    
    override fun getSessionId(): String? = sessionId
    
    override fun getRemoteAddress(): String? = remoteAddress
} 