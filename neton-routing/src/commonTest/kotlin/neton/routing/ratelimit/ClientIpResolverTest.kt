package neton.routing.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpResolverTest {
    private val proxies = setOf("10.0.0.1", "10.0.0.2")

    @Test
    fun `直连时只认对端 伪造的 XFF 不起作用`() {
        assertEquals("203.0.113.9", ClientIpResolver.resolve("203.0.113.9:51000", "1.1.1.1, 2.2.2.2", "9.9.9.9", proxies))
    }

    @Test
    fun `没配可信代理时永远是对端`() {
        assertEquals("10.0.0.1", ClientIpResolver.resolve("10.0.0.1", "1.1.1.1", null, emptySet()))
    }

    @Test
    fun `可信代理之后 从右往左剥掉每一跳可信代理`() {
        assertEquals("198.51.100.7", ClientIpResolver.resolve("10.0.0.1", "1.1.1.1, 198.51.100.7, 10.0.0.2", null, proxies))
    }

    @Test
    fun `客户端伪造的左侧项不被采信 只取最右一个不可信的`() {
        assertEquals("198.51.100.7", ClientIpResolver.resolve("10.0.0.1", "8.8.8.8, 198.51.100.7", null, proxies))
    }

    @Test
    fun `整条链都可信时退回 X-Real-IP 再退回对端`() {
        assertEquals("198.51.100.3", ClientIpResolver.resolve("10.0.0.1", "10.0.0.2", "198.51.100.3", proxies))
        assertEquals("10.0.0.1", ClientIpResolver.resolve("10.0.0.1", "10.0.0.2", null, proxies))
        assertEquals("10.0.0.1", ClientIpResolver.resolve("10.0.0.1", null, null, proxies))
    }

    @Test
    fun `端口被剥掉 IPv6 不被误切`() {
        assertEquals("203.0.113.9", ClientIpResolver.stripPort("203.0.113.9:443"))
        assertEquals("::1", ClientIpResolver.stripPort("[::1]:443"))
        assertEquals("2001:db8::1", ClientIpResolver.stripPort("2001:db8::1"))
    }

    @Test
    fun `对端为空时给出固定占位 而不是把所有人合进一个桶里却看不出来`() {
        assertEquals("unknown", ClientIpResolver.resolve("", "1.1.1.1", null, proxies))
    }
}
