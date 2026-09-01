package neton.http.client

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 渠道级 HTTP 代理（NewGate 网关前置）：create DSL 接受 proxyUrl，非法值 fail-fast。 */
class HttpClientProxyTest {

    @Test
    fun createAcceptsHttpProxyUrl() {
        // 仅验证构造路径：合法 http 代理地址可创建 client（不实际发请求）
        val client = HttpClient.create { proxyUrl = "http://127.0.0.1:1" }
        assertTrue(client is HttpClient)
    }

    @Test
    fun malformedProxyUrlFailsFast() {
        assertFailsWith<HttpClientException> {
            HttpClient.create { proxyUrl = "not-a-url" }
        }
    }

    @Test
    fun socksProxyRejected() {
        // v1 只支持 HTTP 代理（Ktor CIO 不支持 SOCKS）
        assertFailsWith<HttpClientException> {
            HttpClient.create { proxyUrl = "socks5://127.0.0.1:1080" }
        }
    }
}
