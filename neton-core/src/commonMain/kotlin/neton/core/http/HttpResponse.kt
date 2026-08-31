package neton.core.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 响应体序列化 Json。
 *
 * `encodeDefaults = true` 是**契约要求**，不是可选项：kotlinx 的裸 `Json` 默认
 * `encodeDefaults = false`，会把任何「当前值恰好等于声明默认值」的字段**从响应
 * JSON 里整个删掉**。对客户端而言这与「这个字段不存在」无法区分——提现单
 * `fee = 0`、`status = 0(待审核)` 被丢掉后，H5 拿到 `undefined`，渲染出
 * `¥NaN.NaN` 和状态「未知」（2026-07-26 生产实测，一个根因炸三处）。
 *
 * **响应字段集必须由类型决定，不能由运行时取值决定。**
 *
 * 这一条 KSP 生成的路由（`ControllerProcessor.responseJson`）与两个适配器的
 * envelope 序列化都已遵守；本文件是同一条路径上最后一个还在用裸 `Json` 的出口。
 *
 * Kotlin 客户端感知不到这个 bug——它反序列化时会把缺失字段补回声明的默认值。
 * 只有 JS/TS 端才炸，所以它极容易在联调里被漏掉。
 */
private val responseJson = Json { encodeDefaults = true }

/**
 * 把 Map / List / 原始类型转成 [JsonElement]。
 *
 * 这里**不能**用 `Json.encodeToString(serializer(), data)`：`json()` 的形参声明成 `Any`，
 * reified 的 `serializer()` 就只能解析出 `Any` 的序列化器，运行期直接
 * `SerializationException`。也就是说这个分支此前对**任何**对象都是抛异常——
 * 包括限流拦截器传进来的那个 `mapOf("code" to 429, ...)`：触发限流时返回给客户端的
 * 不是 429 JSON，而是一个序列化异常。
 *
 * `@Serializable` 业务对象不走这条路：它们由 KSP 生成的路由预序列化成 `JsonContent`
 * （那条路径自带 `encodeDefaults = true`）。这里只兜底 Map / List / 原始类型，
 * 遇到别的类型显式报错，说清该怎么办——而不是留一个看不懂的序列化异常。
 */
private fun Any?.toResponseJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toResponseJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toResponseJsonElement() })
    is Array<*> -> JsonArray(map { it.toResponseJsonElement() })
    else -> throw IllegalArgumentException(
        "HttpResponse.json() only accepts a pre-encoded String, a JsonElement, or Map/List/primitives. " +
            "Got ${this::class.simpleName}. Serialize @Serializable types with a Json configured " +
            "with encodeDefaults = true (or return them from a controller and let KSP do it).",
    )
}

/**
 * 流式响应体写出器。由 [HttpResponse.stream] 提供，逐块写出响应体。
 * 真流式适配器保证每次 writeChunk 后立即 flush；默认实现缓冲至结束一次性提交。
 */
interface HttpBodyWriter {
    suspend fun writeChunk(chunk: ByteArray)
    suspend fun writeChunk(text: String) = writeChunk(text.encodeToByteArray())
}

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
     * 已写出的响应体字节数，供 access log 的 bytesOut 使用；默认 0。
     */
    val bytesOut: Long get() = 0L
    
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
     * 流式写出响应体。默认实现缓冲全部块后单次 write()（兼容不支持流式的适配器）；
     * 支持真流式的适配器（如 Ktor）应覆写为逐块 flush。
     * 与 write() 相同：调用即视为提交响应，引擎不再用返回值包 envelope。
     */
    suspend fun stream(block: suspend HttpBodyWriter.() -> Unit) {
        val chunks = mutableListOf<ByteArray>()
        val writer = object : HttpBodyWriter {
            override suspend fun writeChunk(chunk: ByteArray) { chunks.add(chunk) }
        }
        writer.block()
        val total = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(total, pos); pos += c.size }
        write(total)
    }
    
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
            else -> responseJson.encodeToString(JsonElement.serializer(), data.toResponseJsonElement())
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