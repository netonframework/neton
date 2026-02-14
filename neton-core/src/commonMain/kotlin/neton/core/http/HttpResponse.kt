package neton.core.http

import kotlinx.serialization.json.JsonObject

/**
 * HTTP 响应接口 - 抽象HTTP响应操作
 */
interface HttpResponse {
    /**
     * HTTP 状态码
     */
    var status: HttpStatus
    
    /**
     * 响应头集合
     */
    val headers: MutableHeaders
    
    /**
     * 是否已提交响应
     */
    val isCommitted: Boolean
    
    /**
     * 内容类型
     */
    var contentType: String?
        get() = headers["Content-Type"]
        set(value) {
            if (value != null) {
                headers["Content-Type"] = value
            } else {
                headers.remove("Content-Type")
            }
        }
    
    /**
     * 内容长度
     */
    var contentLength: Long?
        get() = headers["Content-Length"]?.toLongOrNull()
        set(value) {
            if (value != null) {
                headers["Content-Length"] = value.toString()
            } else {
                headers.remove("Content-Length")
            }
        }
    
    /**
     * 设置响应头
     */
    fun header(name: String, value: String) {
        headers[name] = value
    }
    
    /**
     * 添加响应头（不覆盖已存在的值）
     */
    fun addHeader(name: String, value: String) {
        headers.add(name, value)
    }
    
    /**
     * 移除响应头
     */
    fun removeHeader(name: String) {
        headers.remove(name)
    }
    
    /**
     * 设置Cookie
     */
    fun cookie(cookie: Cookie)
    
    /**
     * 设置Cookie（便捷方法）
     */
    fun cookie(
        name: String,
        value: String,
        domain: String? = null,
        path: String? = null,
        maxAge: Int? = null,
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: Cookie.SameSite? = null
    ) {
        cookie(SimpleCookie(name, value, domain, path, maxAge, secure, httpOnly, sameSite))
    }
    
    /**
     * 删除Cookie
     */
    fun removeCookie(name: String, domain: String? = null, path: String? = null) {
        cookie(name, "", domain, path, maxAge = 0)
    }
    
    /**
     * 写入字节数组
     */
    suspend fun write(data: ByteArray)
    
    /**
     * 写入文本
     */
    suspend fun text(data: String, contentType: String = "text/plain; charset=utf-8") {
        this.contentType = contentType
        write(data.encodeToByteArray())
    }
    
    /**
     * 写入HTML
     */
    suspend fun html(data: String) {
        text(data, "text/html; charset=utf-8")
    }
    
    /**
     * 写入JSON
     */
    suspend fun json(data: Any, contentType: String = "application/json; charset=utf-8") {
        this.contentType = contentType
        val jsonString = when (data) {
            is String -> data
            is JsonObject -> data.toString()
            else -> kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer(), data
            )
        }
        write(jsonString.encodeToByteArray())
    }
    
    /**
     * 重定向
     */
    suspend fun redirect(url: String, status: HttpStatus = HttpStatus.FOUND) {
        this.status = status
        header("Location", url)
    }
    
    /**
     * 永久重定向
     */
    suspend fun redirectPermanent(url: String) {
        redirect(url, HttpStatus.MOVED_PERMANENTLY)
    }
    
    /**
     * 发送错误响应
     */
    suspend fun error(status: HttpStatus, message: String? = null) {
        this.status = status
        val errorMessage = message ?: status.message
        text(errorMessage, "text/plain; charset=utf-8")
    }
    
    /**
     * 发送404错误
     */
    suspend fun notFound(message: String = "Not Found") {
        error(HttpStatus.NOT_FOUND, message)
    }
    
    /**
     * 发送400错误
     */
    suspend fun badRequest(message: String = "Bad Request") {
        error(HttpStatus.BAD_REQUEST, message)
    }
    
    /**
     * 发送401错误
     */
    suspend fun unauthorized(message: String = "Unauthorized") {
        error(HttpStatus.UNAUTHORIZED, message)
    }
    
    /**
     * 发送403错误
     */
    suspend fun forbidden(message: String = "Forbidden") {
        error(HttpStatus.FORBIDDEN, message)
    }
    
    /**
     * 发送500错误
     */
    suspend fun internalServerError(message: String = "Internal Server Error") {
        error(HttpStatus.INTERNAL_SERVER_ERROR, message)
    }
    
    /**
     * 检查状态码是否为成功状态（2xx）
     */
    fun isSuccessful(): Boolean = status.code in 200..299
    
    /**
     * 检查状态码是否为重定向状态（3xx）
     */
    fun isRedirection(): Boolean = status.code in 300..399
    
    /**
     * 检查状态码是否为客户端错误（4xx）
     */
    fun isClientError(): Boolean = status.code in 400..499
    
    /**
     * 检查状态码是否为服务器错误（5xx）
     */
    fun isServerError(): Boolean = status.code in 500..599
}

/**
 * 简单Cookie实现
 */
data class SimpleCookie(
    override val name: String,
    override var value: String,
    override var domain: String? = null,
    override var path: String? = null,
    override var maxAge: Int? = null,
    override var secure: Boolean = false,
    override var httpOnly: Boolean = false,
    override var sameSite: Cookie.SameSite? = null
) : MutableCookie 

/**
 * HttpResponse 扩展函数 - 提供便捷的响应方法
 */

/**
 * 发送文本响应（便捷方法）
 */
suspend fun HttpResponse.text(
    data: String, 
    status: HttpStatus = HttpStatus.OK,
    contentType: String = "text/plain; charset=utf-8"
) {
    this.status = status
    this.contentType = contentType
    write(data.encodeToByteArray())
}

/**
 * 发送JSON响应（便捷方法）
 */
suspend fun HttpResponse.json(
    data: Any,
    status: HttpStatus = HttpStatus.OK,
    contentType: String = "application/json; charset=utf-8"
) {
    this.status = status
    this.contentType = contentType
    val jsonString = when (data) {
        is String -> data
        is kotlinx.serialization.json.JsonObject -> data.toString()
        else -> {
            // 简单的JSON序列化，后续可以扩展
            if (data is Map<*, *>) {
                data.entries.joinToString(
                    prefix = "{", 
                    postfix = "}", 
                    separator = ","
                ) { "\"${it.key}\":\"${it.value}\"" }
            } else {
                "\"$data\""
            }
        }
    }
    write(jsonString.encodeToByteArray())
}

/**
 * 发送字节数组响应（便捷方法）
 */
suspend fun HttpResponse.bytes(
    data: ByteArray,
    status: HttpStatus = HttpStatus.OK,
    contentType: String = "application/octet-stream"
) {
    this.status = status
    this.contentType = contentType
    write(data)
}

/**
 * 发送成功响应（便捷方法）
 */
suspend fun HttpResponse.ok(message: String = "OK") {
    text(message, HttpStatus.OK)
}

/**
 * 发送创建成功响应（便捷方法）
 */
suspend fun HttpResponse.created(data: Any? = null) {
    if (data != null) {
        json(data, HttpStatus.CREATED)
    } else {
        text("Created", HttpStatus.CREATED)
    }
}

/**
 * 发送无内容响应（便捷方法）
 */
suspend fun HttpResponse.noContent() {
    this.status = HttpStatus.NO_CONTENT
    write(ByteArray(0))
} 