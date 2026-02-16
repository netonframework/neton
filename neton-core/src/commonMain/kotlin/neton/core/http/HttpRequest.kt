package neton.core.http

/**
 * HTTP 请求接口 - 抽象HTTP请求访问
 */
interface HttpRequest {
    /**
     * HTTP 方法
     */
    val method: HttpMethod

    /**
     * 请求路径（不包含查询参数）
     */
    val path: String

    /**
     * 完整URL
     */
    val url: String

    /**
     * 协议版本
     */
    val version: String

    /**
     * 请求头集合
     */
    val headers: Headers

    /**
     * 查询参数集合
     */
    val queryParams: Parameters

    /**
     * 路径参数集合（由路由匹配产生）
     */
    val pathParams: Parameters

    /**
     * Cookie 集合
     */
    val cookies: Map<String, Cookie>

    /**
     * 客户端IP地址
     */
    val remoteAddress: String

    /**
     * 用户代理
     */
    val userAgent: String?
        get() = headers["User-Agent"]

    /**
     * 内容类型
     */
    val contentType: String?
        get() = headers["Content-Type"]

    /**
     * 内容长度
     */
    val contentLength: Long?
        get() = headers["Content-Length"]?.toLongOrNull()

    /**
     * 是否为安全连接（HTTPS）
     */
    val isSecure: Boolean

    /**
     * 获取指定Cookie
     */
    fun cookie(name: String): Cookie? = cookies[name]

    /**
     * 获取指定请求头
     */
    fun header(name: String): String? = headers[name]

    /**
     * 获取指定查询参数
     */
    fun queryParam(name: String): String? = queryParams[name]

    /**
     * 获取指定路径参数
     */
    fun pathParam(name: String): String? = pathParams[name]

    /**
     * 读取请求体为字节数组
     */
    suspend fun body(): ByteArray

    /**
     * 读取请求体为文本
     */
    suspend fun text(): String

    /**
     * 读取请求体为JSON数据
     */
    suspend fun json(): Any

    /**
     * 读取表单数据
     */
    suspend fun form(): Parameters

    /**
     * 读取上传文件
     */
    suspend fun uploadFiles(): UploadFiles = UploadFiles(emptyList())

    /**
     * 是否为 multipart 请求
     */
    fun isMultipart(): Boolean {
        val ct = contentType ?: return false
        return ct.contains("multipart/form-data")
    }

    /**
     * 检查是否为指定的HTTP方法
     */
    fun isMethod(method: HttpMethod): Boolean = this.method == method

    /**
     * 检查是否为GET请求
     */
    fun isGet(): Boolean = isMethod(HttpMethod.GET)

    /**
     * 检查是否为POST请求
     */
    fun isPost(): Boolean = isMethod(HttpMethod.POST)

    /**
     * 检查是否为PUT请求
     */
    fun isPut(): Boolean = isMethod(HttpMethod.PUT)

    /**
     * 检查是否为DELETE请求
     */
    fun isDelete(): Boolean = isMethod(HttpMethod.DELETE)

    /**
     * 检查是否为PATCH请求
     */
    fun isPatch(): Boolean = isMethod(HttpMethod.PATCH)

    /**
     * 检查是否为Ajax请求
     */
    fun isAjax(): Boolean = header("X-Requested-With") == "XMLHttpRequest"

    /**
     * 检查是否接受指定的内容类型
     */
    fun accepts(contentType: String): Boolean {
        val accept = header("Accept") ?: return false
        return accept.contains(contentType) || accept.contains("*/*")
    }

    /**
     * 检查是否接受JSON
     */
    fun acceptsJson(): Boolean = accepts("application/json")

    /**
     * 检查是否接受HTML
     */
    fun acceptsHtml(): Boolean = accepts("text/html")
}
