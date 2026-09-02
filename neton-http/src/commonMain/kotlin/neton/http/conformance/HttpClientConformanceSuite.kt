package neton.http.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk

/**
 * 客户端一致性套件（spec zh-hans/spec/http-engine.md §6）。
 *
 * 抽象层的价值 = 「多个引擎行为一致」的**可验证**程度。Server 侧的套件已经把
 * hyper4k 与 Ktor 的翻译层钉住；这里对 Client 侧做同一件事。没有它，
 * 「hyper4k client 与 Ktor client 等价」只是一句话。
 *
 * ## 怎么接
 *
 * 引擎模块继承本类，实现 [createClient] 与 [recordSkipped]，然后为每个 `check*`
 * 写一个 `@Test` 包装（K/N 的测试框架不继承基类的 @Test）。对端由本类自带的
 * [ScriptedOrigin] 提供，不依赖任何引擎的 Server 侧。
 *
 * ## 能力守卫
 *
 * 声明了某能力就必须真跑对应测试；没声明就记为 skipped。**声明了却跳过 =
 * 构建失败**——这是防「声明先行、实现拖延」的唯一闸门（§6.3）。
 *
 * `HTTP_2` 与 `CUSTOM_CA` 的对端超出最小 origin 的能力，对应的 `check*` 默认
 * 在声明时抛出，引擎模块**必须覆写**它们并自带 h2 / TLS 对端。
 */
public abstract class HttpClientConformanceSuite {

    /** 被测客户端。实现方通常 `HttpClient.createWith(::createXxxClient, block)`。 */
    public abstract fun createClient(block: HttpClientConfig.() -> Unit = {}): HttpClient

    /**
     * 记录一次因能力缺失而跳过。**必须显式，不得静默通过**：一个能力全被跳过、
     * 报告却全绿的套件等于没有套件。
     */
    public abstract fun recordSkipped(capability: HttpClientCapability, testName: String)

    protected suspend fun requiring(
        capability: HttpClientCapability,
        testName: String,
        block: suspend () -> Unit,
    ) {
        val probe = createClient()
        val declared = try {
            capability in probe.capabilities
        } finally {
            probe.close()
        }
        if (declared) block() else recordSkipped(capability, testName)
    }

    // ---------------- 所有引擎都必须通过 ----------------

    public suspend fun checkGetReturnsStatusHeadersAndBody(): Unit = withOrigin(
        handle = { writeFixed(201, HttpHeaders.of("X-Origin" to "seen"), "hello".encodeToByteArray()) },
    ) { origin, client ->
        val response = client.request(get("${origin.baseUrl}/path?q=1"))
        check(response.statusCode == 201) { "expected 201, got ${response.statusCode}" }
        check(response.body == "hello") { "expected body 'hello', got '${response.body}'" }
        // 查找不分大小写；线上的名字怎么写是引擎的事，调用方不该关心。
        check(response.headers.get("x-origin") == "seen") { "header lookup must be case-insensitive: ${response.headers}" }
        val seen = origin.requests.single()
        check(seen.method == "GET") { "origin saw method ${seen.method}" }
        // origin-form (RFC 9112 §3.2.1): a direct connection must not carry the
        // scheme and authority in the request-target; only proxies see that form.
        check(seen.target == "/path?q=1") { "origin saw target ${seen.target}" }
        // Host is mandatory in HTTP/1.1; a missing one is a 400 from most servers.
        check(seen.headers.get("host")?.startsWith("127.0.0.1") == true) { "Host header missing or wrong: ${seen.headers}" }
    }

    public suspend fun checkRequestBodyBytesAreVerbatim(): Unit = withOrigin(
        handle = { request -> writeFixed(200, body = request.body.size.toString().encodeToByteArray()) },
    ) { origin, client ->
        // 含 NUL、高位字节和非法 UTF-8 序列：任何一处经过 String 往返都会变形。
        val payload = ByteArray(300) { i -> (i * 7 and 0xFF).toByte() }.also {
            it[0] = 0x00; it[1] = 0xFF.toByte(); it[2] = 0xC3.toByte(); it[3] = 0x28
        }
        val response = client.request(
            HttpClientRequest(
                method = HttpClientMethod.Post,
                url = "${origin.baseUrl}/upload",
                body = HttpClientBody.Bytes(payload, "application/octet-stream"),
            ),
        )
        check(response.body == payload.size.toString()) { "origin reported ${response.body} bytes" }
        val seen = origin.requests.single()
        check(seen.body.contentEquals(payload)) { "request body was altered in transit" }
    }

    public suspend fun checkHeadersPreserveMultiValueAndCaseInsensitiveLookup(): Unit = withOrigin(
        handle = {
            writeFixed(
                200,
                HttpHeaders.of("Set-Cookie" to "a=1", "Set-Cookie" to "b=2", "Content-Type" to "text/plain"),
                "ok".encodeToByteArray(),
            )
        },
    ) { origin, client ->
        val response = client.request(
            HttpClientRequest(
                method = HttpClientMethod.Get,
                url = "${origin.baseUrl}/",
                headers = HttpHeaders.of("X-Multi" to "first", "X-Multi" to "second"),
            ),
        )
        // 重复的 Set-Cookie 曾经被 firstOrNull() 悄悄丢掉一半——这是有前科的。
        val cookies = response.headers.getAll("set-cookie")
        check(cookies == listOf("a=1", "b=2")) {
            "repeated response headers lost: ${cookies.size} value(s) ${cookies.map { "'$it'" }}"
        }
        // 请求侧放宽到 RFC 7230 §3.2.2：重复的 field 可以合并成一行逗号分隔。
        // 响应侧的 Set-Cookie 不放宽——它被 RFC 6265 明确排除在合并规则之外，
        // 合并后 Expires 里的逗号就再也拆不开了。
        val sent = origin.requests.single().headers.getAll("x-multi")
        val combined = sent.flatMap { it.split(',') }.map { it.trim() }
        check(combined == listOf("first", "second")) { "repeated request headers lost: $sent" }
    }

    /**
     * `request()` 不把 4xx/5xx 变成异常：状态码是响应的一部分，调用方按业务决定
     * 怎么处理（S3 的 404 是"不存在"，不是错误）。这是既有契约，两个引擎必须一致。
     */
    public suspend fun checkRequestReturnsNonSuccessStatusAsAResponse(): Unit = withOrigin(
        handle = { writeFixed(503, body = "down".encodeToByteArray()) },
    ) { origin, client ->
        val response = client.request(get("${origin.baseUrl}/"))
        check(response.statusCode == 503) { "status ${response.statusCode}" }
        check(response.body == "down") { "body must be preserved on error status, got '${response.body}'" }
    }

    /**
     * `stream()` 则相反：非 2xx **必须**在第一个 chunk 之前以 [HttpClientError.Http]
     * 抛出。否则一个 429 的错误 JSON 会流进 SSE 解析器，变成零个事件和一个静默的
     * "正常结束"。
     */
    public suspend fun checkStreamThrowsHttpErrorForNonSuccessStatus(): Unit = withOrigin(
        handle = { writeFixed(429, body = """{"error":"slow down"}""".encodeToByteArray()) },
    ) { origin, client ->
        val error = expectFailure { client.stream(get("${origin.baseUrl}/events")).toList() }
        val http = error as? HttpClientError.Http
            ?: error("429 on stream() must surface as HttpClientError.Http, got $error")
        check(http.statusCode == 429) { "status ${http.statusCode}" }
        check(http.body?.contains("slow down") == true) { "error body must be preserved, got '${http.body}'" }
    }

    public suspend fun checkConnectionRefusedMapsToNetwork() {
        val port = ScriptedOrigin.unusedPort()
        val client = createClient()
        try {
            val error = expectFailure { client.request(get("http://127.0.0.1:$port/")) }
            check(error is HttpClientError.Network) { "connection refused must be Network, got $error" }
        } finally {
            client.close()
        }
    }

    public suspend fun checkRequestTimeoutMapsToTimeout(): Unit = withOrigin(
        // 收下请求，然后什么都不写。
        handle = { delay(30_000) },
        configure = { requestMillis = 300 },
    ) { origin, client ->
        val error = expectFailure { client.request(get("${origin.baseUrl}/slow")) }
        check(error is HttpClientError.Timeout) { "stalled response must be Timeout, got $error" }
    }

    public suspend fun checkCloseIsIdempotentAndRejectsFurtherRequests(): Unit = withOrigin(
        handle = { writeFixed(200) },
    ) { origin, client ->
        client.close()
        client.close()
        // 关掉之后再用，必须是本抽象的异常，不能是引擎内部的随便什么东西——
        // 调用方只认识 HttpClientException。
        expectFailure { client.request(get("${origin.baseUrl}/")) }
    }

    // ---------------- 按能力条件执行 ----------------

    public suspend fun checkStreamingChunksEmitBeforeBodyCompletes(): Unit =
        requiring(HttpClientCapability.STREAMING_BODY, "streaming_chunks_emit_before_body_completes") {
            // 缓冲实现与流式实现的最终字节完全相同，只有时序不同。所以用一道门：
            // origin 写完第一块后等客户端确认收到，才写第二块。缓冲实现在这里死锁。
            val firstSeen = CompletableDeferred<Unit>()
            withOrigin(
                handle = {
                    writeHead(200)
                    writeChunk("first".encodeToByteArray())
                    firstSeen.await()
                    writeChunk("second".encodeToByteArray())
                    end()
                },
            ) { origin, client ->
                val chunks = client.stream(get("${origin.baseUrl}/stream")).toList {
                    if (it is HttpClientStreamChunk.Bytes && !firstSeen.isCompleted) firstSeen.complete(Unit)
                }
                val body = chunks.filterIsInstance<HttpClientStreamChunk.Bytes>()
                    .fold(ByteArray(0)) { acc, c -> acc + c.bytes }.decodeToString()
                check(body == "firstsecond") { "stream body '$body'" }
                check(chunks.last() is HttpClientStreamChunk.End) { "stream must end with End" }
            }
        }

    public suspend fun checkFlowCancellationClosesTheConnection(): Unit =
        requiring(HttpClientCapability.CANCELLATION, "flow_cancellation_closes_the_connection") {
            val peerClosed = CompletableDeferred<Boolean>()
            withOrigin(
                handle = {
                    writeHead(200)
                    writeChunk("first".encodeToByteArray())
                    // 不写 end()：连接只会因为客户端主动断开而结束。
                    peerClosed.complete(awaitPeerClosed(5_000))
                },
            ) { origin, client ->
                client.stream(get("${origin.baseUrl}/stream")).first { it is HttpClientStreamChunk.Bytes }
                // first() 取消了 Flow。取消必须传到连接上，否则服务端会一直挂着。
                check(withTimeout(6_000) { peerClosed.await() }) { "origin never saw the connection close after Flow cancellation" }
            }
        }

    public suspend fun checkProxyUrlRoutesThroughTheProxy(): Unit =
        requiring(HttpClientCapability.PROXY, "proxy_url_routes_through_the_proxy") {
            // origin 扮演 HTTP 代理：它该收到的是绝对 URI 的请求行。
            withOrigin(
                handle = { writeFixed(200, body = "via-proxy".encodeToByteArray()) },
                configure = { proxyUrl = originUrlForProxy() },
            ) { origin, client ->
                val response = client.request(get("http://example.invalid:8080/resource"))
                check(response.body == "via-proxy") { "response did not come from the proxy: '${response.body}'" }
                val seen = origin.requests.single()
                check(seen.target.startsWith("http://example.invalid:8080/resource")) {
                    "a proxied request must carry an absolute URI, got '${seen.requestLine}'"
                }
            }
        }

    /** 需要 h2 对端；声明了 `HTTP_2` 的引擎必须覆写。 */
    public open suspend fun checkHttp2IsNegotiatedWhenOriginOffersIt(): Unit =
        requiring(HttpClientCapability.HTTP_2, "http2_is_negotiated_when_origin_offers_it") {
            throw UnsupportedOperationException(
                "this client declares HTTP_2 but did not override checkHttp2IsNegotiatedWhenOriginOffersIt " +
                    "with an h2-capable origin",
            )
        }

    /** 需要 TLS 对端；声明了 `CUSTOM_CA` 的引擎必须覆写。 */
    public open suspend fun checkCustomCaIsTrustedAndSystemCaIsNot(): Unit =
        requiring(HttpClientCapability.CUSTOM_CA, "custom_ca_is_trusted_and_system_ca_is_not") {
            throw UnsupportedOperationException(
                "this client declares CUSTOM_CA but did not override checkCustomCaIsTrustedAndSystemCaIsNot " +
                    "with a TLS origin",
            )
        }

    // ---------------- 内部 ----------------

    private var proxyOriginUrl: String? = null
    private fun originUrlForProxy(): String = proxyOriginUrl ?: error("proxy origin not started")

    private suspend fun withOrigin(
        handle: suspend OriginConnection.(RecordedRequest) -> Unit,
        configure: HttpClientConfig.() -> Unit = {},
        body: suspend (origin: ScriptedOrigin, client: HttpClient) -> Unit,
    ) {
        val origin = ScriptedOrigin.start(handle)
        proxyOriginUrl = origin.baseUrl
        val client = createClient(configure)
        try {
            withTimeout(20_000) { body(origin, client) }
        } finally {
            client.close()
            origin.stop()
            proxyOriginUrl = null
        }
    }

    private fun get(url: String) = HttpClientRequest(method = HttpClientMethod.Get, url = url)

    private suspend fun expectFailure(block: suspend () -> Unit): HttpClientError {
        try {
            block()
        } catch (e: HttpClientException) {
            return e.error
        }
        error("expected HttpClientException, but the call succeeded")
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.toList(onEach: (T) -> Unit): List<T> {
        val out = mutableListOf<T>()
        collect { onEach(it); out += it }
        return out
    }
}
