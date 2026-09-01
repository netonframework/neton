package neton.http.ktor



import neton.core.http.adapter.HttpServerConfig

import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.core.http.Cookie
import neton.core.http.HttpBodyWriter
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
import neton.core.http.MutableHeaders
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse
import neton.logging.LoggerFactory
import neton.logging.emptyFields
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.readByteArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


/**
 * Ktor CIO HTTP 适配器 —— 薄传输层。
 *
 * 与 may4k / hyper4k 一样，只负责 socket 与字节搬运：请求进来先整体缓冲成
 * [BufferedHttpRequest]，交给共享的 [BufferedHttpDispatcher]（路由 / 安全 / 限流 /
 * CORS / envelope / access log 全部在那里，三个引擎一份实现）；响应通过
 * [KtorLiveResponse] 真流式写回（SSE / relay 依赖），未提交的返回值由本类一次性写出。
 *
 * Ktor 定位为**开发服务器**（错误信息友好、生态成熟），生产主推 hyper4k。
 */
class KtorHttpAdapter(
    private val serverConfig: HttpServerConfig,
    @Suppress("UNUSED_PARAMETER")
    private val paramConverterRegistry: neton.core.http.ParamConverterRegistry = neton.core.http.DefaultParamConverterRegistry()
) : HttpAdapter {

    private val dispatcher = BufferedHttpDispatcher(serverConfig)
    private var embeddedServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var appContext: NetonContext? = null

    override fun port(): Int = serverConfig.port
    override fun adapterName(): String = "Ktor CIO"

    /**
     * Ktor CIO 的实际能力（spec http-engine-capabilities §3）。
     *
     * **没有 `HTTP_2`，而且这不是待办**：Ktor 的 HTTP/2 只在 Netty / Jetty 引擎上，
     * 那两个是 JVM-only；Kotlin/Native 目标只有 CIO。实测
     * `ktor-server-cio-*Main-3.5.1.klib` 中 http2 / h2c 相关符号为 0。
     * 需要 HTTP/2 的应用应改用 hyper4k 适配器。
     *
     * 同理没有 `TRAILERS`。
     */
    override val capabilities: Set<HttpCapability> = setOf(
        HttpCapability.STREAMING_RESPONSE,
        HttpCapability.MULTIPART,
        HttpCapability.ASYNC_HANDOFF,
    )

    private fun log(): neton.logging.Logger? = appContext?.getOrNull(LoggerFactory::class)?.get("neton.http")

    override suspend fun start(ctx: NetonContext, onStarted: (suspend (coldStartMs: Long) -> Unit)?) {
        appContext = ctx
        dispatcher.bind(ctx)
        run(serverConfig.port, ctx.args, onStarted)
    }

    private fun isPortInUse(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message ?: ""
            val name = t::class.simpleName ?: ""
            if (msg.contains("EADDRINUSE") || msg.contains("Address already in use") || name.contains("AddressAlreadyInUse")) return true
            t = t.cause
        }
        return false
    }

    private suspend fun run(port: Int, args: Array<String>, onStarted: (suspend (coldStartMs: Long) -> Unit)? = null) {
        val startMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        // KTOR_LOG_LEVEL 与框架 logging.level 同步
        val appConfig = ConfigLoader.loadApplicationConfig("config", ConfigLoader.resolveEnvironment(args), args)

        @Suppress("UNCHECKED_CAST")
        val loggingSection = appConfig?.let { ConfigLoader.getConfigValue(it, "logging") as? Map<String, Any?> }
        val levelStr = (loggingSection?.get("level") as? String)?.uppercase() ?: "INFO"
        val ktorLevel = when (levelStr) {
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR" -> levelStr
            else -> "INFO"
        }
        syncKtorLogLevelToConfig(ktorLevel)
        installPortInUseHandler(port)
        try {
            embeddedServer = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                // 不装 ContentNegotiation / IgnoreTrailingSlash / CORS 插件：
                // envelope、尾斜杠归一、CORS 全部由 BufferedHttpDispatcher 统一处理，
                // 三个引擎行为一致（旧版插件路径曾与 may4k/hyper4k 各自漂移）。
                routing {
                    // 根级裸 handle 在 Ktor 里不是 catch-all（只命中根路由自身），
                    // 必须用 "{...}" tailcard 吃掉任意路径段，所有调用才都会到这里。
                    route("{...}") {
                        handle { handleCall(call) }
                    }
                }
            }

            try {
                // Ready 横幅只在**真的绑上端口之后**打。
                //
                // 这里原来是 `delay(150)` 然后无条件回调：150 毫秒不是「启动成功」的证据，
                // 只是一个赌注。端口被占时 bind 恰好败得比 150ms 快，所以平时看不出来；
                // 机器一慢，就会先打「Ready → http://localhost:8080」再退出，运维照着
                // 这行字判断服务起来了。启动成功要由 Ktor 自己的 ServerReady 事件说了算。
                coroutineScope {
                    val readyJob = launch {
                        embeddedServer?.engine?.resolvedConnectors()
                        val coldStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - startMs
                        onStarted?.invoke(coldStartMs)
                    }
                    try {
                        embeddedServer?.start(wait = true)
                    } catch (e: Throwable) {
                        readyJob.cancel() // bind 失败就别再打 Ready 了
                        throw e
                    }
                }
            } catch (e: Throwable) {
                gracefulShutdown(propagateFailure = false)
                throw e
            }
        } catch (e: Throwable) {
            // 端口占用只在这一个出口报一次。
            //
            // 之前三层 catch 各打一遍同一句话，加上 native 的 unhandled hook 还打一遍——
            // 同一次失败刷出 2~3 行「Port 8080 is already in use」。这不只是难看：我们据此
            // 写进部署文档的根因是「进程自己绑了两次 8080」，完全错了，此后每次生产重启都
            // 按那个错误结论在赌。重复的错误输出会变成错误的结论。
            if (isPortInUse(e)) {
                reportPortInUseOnce(port)
            } else {
                log()?.error("Failed to start Ktor server", mapOf("port" to port), cause = e)
            }
            gracefulShutdown(propagateFailure = false)
            throw e
        } finally {
            // 确保在任何情况下都执行清理
            // 如果是正常退出（比如 Ctrl+C），也执行优雅关闭
            if (embeddedServer != null) {
                gracefulShutdown(propagateFailure = false)
            }
        }
    }

    override suspend fun stop() {
        try {
            gracefulShutdown(propagateFailure = true)
        } finally {
            appContext = null
        }
    }

    private fun gracefulShutdown(propagateFailure: Boolean) {
        try {
            if (embeddedServer != null) {
                embeddedServer?.stop(gracePeriodMillis = 2000, timeoutMillis = 5000)
            }
        } catch (e: Exception) {
            log()?.error("Error during graceful shutdown", emptyFields(), cause = e)
            if (propagateFailure) throw e
        } finally {
            embeddedServer = null
        }
    }

    /**
     * 单个请求的完整生命周期：缓冲请求 → 共享调度器 → 写回。
     * handler 已通过 [KtorLiveResponse] 提交（流式/直写）时不再二次写出。
     */
    private suspend fun handleCall(call: io.ktor.server.application.ApplicationCall) {
        val uri = call.request.uri
        val headers = mutableMapOf<String, List<String>>()
        for (name in call.request.headers.names()) {
            headers[name] = call.request.headers.getAll(name) ?: emptyList()
        }
        // ContentNegotiation 未安装，receiveChannel 直读原始字节，不会被插件链拦截。
        val body = call.receiveChannel().readRemaining().readByteArray()
        val request = BufferedHttpRequest(
            method = call.request.httpMethod.value,
            path = uri.substringBefore('?'),
            query = uri.substringAfter('?', ""),
            headers = headers,
            body = body,
            remoteAddress = call.request.local.remoteAddress,
        )
        // CORS 头必须在提交**前**注入：流式响应一旦开始写就补不上头了。
        val live = KtorLiveResponse(call, dispatcher.corsHeaders(request))
        val result = dispatcher.dispatch(request, live)
        if (!live.isCommitted) {
            respondBuffered(call, result)
        }
    }

    /** handler 未提交时，把调度器返回的缓冲响应整体写出（envelope / 404 / 429 都走这里）。 */
    private suspend fun respondBuffered(
        call: io.ktor.server.application.ApplicationCall,
        result: BufferedHttpResponse,
    ) {
        for ((name, values) in result.headers) {
            // Content-Type 由 respondBytes 的参数管理；Content-Length 由引擎计算。
            if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
            for (value in values) call.response.headers.append(name, value)
        }
        val contentTypeText = result.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
        val contentType = contentTypeText?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
        call.respondBytes(result.body, contentType, HttpStatusCode.fromValue(result.status))
    }
}

/**
 * 真流式 HttpResponse：handler 直接写 Ktor 响应（SSE / relay 依赖）。
 *
 * v1.1 方案 B：所有「提交」入口（write/text/json/redirect/error 等）统一置 isCommitted；
 * commit 后禁止二次写（fail-fast）；status 在 commit 前由各 API 设置，
 * access log 用 response.status.code / bytesOut。
 */
private class KtorLiveResponse(
    private val call: io.ktor.server.application.ApplicationCall,
    private val corsHeaders: Map<String, List<String>>,
) : HttpResponse {

    override val headers: MutableHeaders = SimpleMutableHeaders()

    override var status: HttpStatus = HttpStatus.OK

    override fun cookie(cookie: Cookie) {}

    private var _committed = false
    override val isCommitted: Boolean get() = _committed

    /** 已写出的 body 字节数，供 access log bytesOut 使用。 */
    private var writtenBytes: Long = 0L
    override val bytesOut: Long get() = writtenBytes

    private fun ensureNotCommitted() {
        if (_committed) throw neton.core.http.HttpException(
            neton.core.http.NetonErrorCode.INTERNAL_ERROR,
            "Response already committed (ResponseAlreadyCommitted)"
        )
    }

    /** 把业务侧 header() 设置的响应头应用到 Ktor（Content-Type/Length 由引擎管理，跳过）。 */
    private fun applyHeadersToCall() {
        for (name in headers.names()) {
            if (name.equals("Content-Type", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
            for (value in headers.getAll(name)) call.response.headers.append(name, value)
        }
        for ((name, values) in corsHeaders) {
            for (value in values) call.response.headers.append(name, value)
        }
    }

    override suspend fun write(data: ByteArray) {
        ensureNotCommitted()
        _committed = true
        writtenBytes = data.size.toLong()
        applyHeadersToCall()
        val ct = ContentType.parse(contentType ?: "application/octet-stream")
        call.respondBytes(data, ct, HttpStatusCode.fromValue(status.code))
    }

    /**
     * 真流式写出：respondBytesWriter 逐块 writeFully+flush。
     * 客户端断连时写通道抛取消/IO 异常，向上冒泡以取消 block 内的上游拉取。
     */
    override suspend fun stream(block: suspend HttpBodyWriter.() -> Unit) {
        ensureNotCommitted()
        _committed = true
        applyHeadersToCall()
        val ct = ContentType.parse(contentType ?: "application/octet-stream")
        var bytesOut = 0L
        call.respondBytesWriter(contentType = ct, status = HttpStatusCode.fromValue(status.code)) {
            val channel = this
            val writer = object : HttpBodyWriter {
                override suspend fun writeChunk(chunk: ByteArray) {
                    channel.writeFully(chunk, 0, chunk.size)
                    channel.flush()
                    bytesOut += chunk.size
                }
            }
            writer.block()
        }
        writtenBytes = bytesOut
    }

    /** redirect() 在 core 中不调用 write()，必须在此实现中显式 commit 并发送重定向。 */
    override suspend fun redirect(url: String, status: HttpStatus) {
        ensureNotCommitted()
        _committed = true
        this.status = status
        header("Location", url)
        val permanent = status == HttpStatus.MOVED_PERMANENTLY
        call.respondRedirect(url, permanent)
    }
}

/**
 * 简化的 MutableHeaders 实现（小写键归一）
 */
private class SimpleMutableHeaders : MutableHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()
    private fun key(name: String) = name.lowercase()
    override fun get(name: String): String? = map[key(name)]?.firstOrNull()
    override fun getAll(name: String): List<String> = map[key(name)] ?: emptyList()
    override fun contains(name: String): Boolean = map.containsKey(key(name))
    override fun names(): Set<String> = map.keys
    override fun toMap(): Map<String, List<String>> = map
    override fun set(name: String, value: String) { map[key(name)] = mutableListOf(value) }
    override fun add(name: String, value: String) { map.getOrPut(key(name)) { mutableListOf() }.add(value) }
    override fun remove(name: String) { map.remove(key(name)) }
    override fun clear() { map.clear() }
}
