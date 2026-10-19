package neton.ai.internal

import kotlinx.coroutines.flow.Flow
import neton.http.client.HttpClient
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import neton.http.client.HttpClientTimeouts

/**
 * 给某个 provider 的所有出站请求补上它自己的超时（provider 配置的 `timeoutMillis`）。
 *
 * AI 网关借用应用共享的 HttpClient（短信、支付、存储也用它，默认超时按这些短调用来定）；
 * 模型生成动辄几十秒，不能靠放宽整个应用的出站超时来容纳。这里按请求补齐：请求自己已带的超时字段
 * （如调用级 [neton.ai.AiTimeout.requestMs]）优先，没带的字段才用 provider 的值。
 * `requestMillis` 与 `socketMillis` 同取该值；`connectMillis` 不动（沿用客户端的连接超时）。
 * 不持有底层客户端的所有权：[close] 不关闭它。
 */
internal class ProviderTimeoutHttpClient(
    private val delegate: HttpClient,
    private val timeoutMillis: Long,
) : HttpClient {
    init {
        require(timeoutMillis > 0) { "provider timeoutMillis must be > 0" }
    }

    internal fun withDefaults(request: HttpClientRequest): HttpClientRequest {
        val t = request.timeout
        return request.copy(
            timeout = HttpClientTimeouts(
                connectMillis = t?.connectMillis,
                requestMillis = t?.requestMillis ?: timeoutMillis,
                socketMillis = t?.socketMillis ?: timeoutMillis,
            ),
        )
    }

    override suspend fun request(request: HttpClientRequest): HttpClientResponse = delegate.request(withDefaults(request))

    override fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk> = delegate.stream(withDefaults(request))

    /** 借用的客户端由创建者关闭。 */
    override suspend fun close() = Unit

    override val capabilities: Set<HttpClientCapability> get() = delegate.capabilities
}
