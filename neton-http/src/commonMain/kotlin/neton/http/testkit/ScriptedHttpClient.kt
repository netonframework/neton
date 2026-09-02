package neton.http.testkit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import neton.http.client.HttpClient
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk

/**
 * 测试替身：按脚本回应的 [HttpClient]（spec zh-hans/spec/http-engine.md §6.4）。
 *
 * 它是接口的**直接实现**，不经任何引擎，所以在零引擎的测试类路径上也能用——
 * 这正是它存在的理由：消费方（neton-ai 等）的测试不能再依赖 Ktor 的 MockEngine，
 * 否则「Ktor 可删」就只是名义上的。
 *
 * 匹配按登记顺序，取第一条 `method` 相同且 `url` 以 `urlPrefix` 开头的脚本。
 * 没有脚本命中时抛 [HttpClientError.Network]——而不是返回一个空 200——
 * 让「测试忘了登记」立刻暴露，而不是让被测代码在空响应上走出奇怪的分支。
 */
class ScriptedHttpClient : HttpClient {

    private class Script(
        val method: HttpClientMethod,
        val urlPrefix: String,
        val respond: (suspend (HttpClientRequest) -> HttpClientResponse)?,
        val stream: (suspend (HttpClientRequest) -> List<HttpClientStreamChunk>)?,
    )

    private val scripts = mutableListOf<Script>()
    private val _recorded = mutableListOf<HttpClientRequest>()
    private var closed = false

    /** 收到过的全部请求，按到达顺序。断言「发了什么」看这里。 */
    val recorded: List<HttpClientRequest> get() = _recorded.toList()

    /** 是否已 [close]。用来断言借用方没有越权关闭它。 */
    val isClosed: Boolean get() = closed

    /** 替身声明全集：它不是引擎，没有能力缺口可言。 */
    override val capabilities: Set<HttpClientCapability> = HttpClientCapability.entries.toSet()

    fun on(
        method: HttpClientMethod,
        urlPrefix: String,
        respond: suspend (HttpClientRequest) -> HttpClientResponse,
    ): ScriptedHttpClient = apply {
        scripts += Script(method, urlPrefix, respond, stream = null)
    }

    fun onStream(
        method: HttpClientMethod,
        urlPrefix: String,
        chunks: List<HttpClientStreamChunk>,
    ): ScriptedHttpClient = onStream(method, urlPrefix) { chunks }

    /**
     * 脚本化的流式响应。lambda 可以抛 [HttpClientException]，用来模拟真实客户端在
     * 第一个 chunk 之前就以 `Http(4xx/5xx)` 失败的契约。
     */
    fun onStream(
        method: HttpClientMethod,
        urlPrefix: String,
        respond: suspend (HttpClientRequest) -> List<HttpClientStreamChunk>,
    ): ScriptedHttpClient = apply {
        scripts += Script(method, urlPrefix, respond = null, stream = respond)
    }

    override suspend fun request(request: HttpClientRequest): HttpClientResponse {
        record(request)
        val script = match(request) ?: throw noScript(request)
        val respond = script.respond
            ?: throw HttpClientException(
                HttpClientError.Unknown(
                    "script for ${request.method} ${request.url} is a stream; call stream() not request()",
                    null,
                ),
            )
        return respond(request)
    }

    override fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk> = flow {
        record(request)
        val script = match(request) ?: throw noScript(request)
        val stream = script.stream
            ?: throw HttpClientException(
                HttpClientError.Unknown(
                    "script for ${request.method} ${request.url} is buffered; call request() not stream()",
                    null,
                ),
            )
        for (chunk in stream(request)) emit(chunk)
    }

    override suspend fun close() {
        closed = true
    }

    private fun record(request: HttpClientRequest) {
        if (closed) {
            throw HttpClientException(HttpClientError.Unknown("ScriptedHttpClient is closed", null))
        }
        _recorded += request
    }

    private fun match(request: HttpClientRequest): Script? =
        scripts.firstOrNull { it.method == request.method && request.url.startsWith(it.urlPrefix) }

    private fun noScript(request: HttpClientRequest) = HttpClientException(
        HttpClientError.Network(
            "no script for ${request.method} ${request.url}; registered: " +
                scripts.joinToString { "${it.method} ${it.urlPrefix}" },
            null,
        ),
    )
}
