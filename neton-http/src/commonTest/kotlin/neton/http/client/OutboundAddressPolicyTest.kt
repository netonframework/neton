package neton.http.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class OutboundAddressPolicyTest {

    private fun ip(s: String) = IpAddress.parse(s) ?: error("bad literal $s")

    /** A resolver answering from a table; names not in it fail like a missing DNS entry. */
    private class TableResolver(private val table: Map<String, List<String>>) : HostResolver {
        val lookups = mutableListOf<String>()
        override suspend fun resolve(host: String): List<IpAddress> {
            lookups += host
            val answer = table[host] ?: throw IllegalStateException("NXDOMAIN $host")
            return answer.map { IpAddress.parse(it)!! }
        }
    }

    private val noDns = HostResolver { fail("no lookup expected for $it") }

    private suspend fun rejection(block: suspend () -> Unit): TargetRejection {
        try {
            block()
        } catch (e: HttpClientException) {
            val err = e.error
            if (err is HttpClientError.TargetRejected) return err.reason
            throw e
        }
        fail("expected the target to be rejected")
    }

    // ---------------------------------------------------------------- address classification

    @Test
    fun everyBlockedIpv4RangeIsNotPublic() {
        listOf(
            "0.0.0.0", "0.255.255.255", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "127.255.255.254", "169.254.169.254", "169.254.0.1", "172.16.0.1", "172.31.255.255",
            "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.0.1", "192.168.255.255", "198.18.0.1",
            "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.255", "240.0.0.1",
            "255.255.255.254", "255.255.255.255",
        ).forEach { assertFalse(OutboundAddressPolicy.isPublic(ip(it)), it) }
    }

    @Test
    fun neighboursOfBlockedIpv4RangesArePublic() {
        listOf(
            "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
            "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.0", "192.0.3.0",
            "192.88.98.255", "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "198.51.99.255",
            "203.0.112.255", "203.0.114.0", "223.255.255.255", "93.184.216.34",
        ).forEach { assertTrue(OutboundAddressPolicy.isPublic(ip(it)), it) }
    }

    @Test
    fun everyBlockedIpv6RangeIsNotPublic() {
        listOf(
            "::", "::1", "fc00::1", "fdff:ffff::1", "fe80::1", "febf::1", "ff02::1", "ff0e::1", "2001:db8::1",
            "2001:db8:ffff::1", "fec0::1", "100::1", "2001::1", "2001:10::1", "64:ff9b:1::1",
        ).forEach { assertFalse(OutboundAddressPolicy.isPublic(ip(it)), it) }
        listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2400:cb00::1").forEach {
            assertTrue(OutboundAddressPolicy.isPublic(ip(it)), it)
        }
    }

    @Test
    fun ipv6CarryingIpv4IsJudgedByTheIpv4() {
        // IPv4-mapped
        assertFalse(OutboundAddressPolicy.isPublic(ip("::ffff:127.0.0.1")))
        assertFalse(OutboundAddressPolicy.isPublic(ip("::ffff:7f00:1")))
        assertFalse(OutboundAddressPolicy.isPublic(ip("::ffff:169.254.169.254")))
        assertFalse(OutboundAddressPolicy.isPublic(ip("::ffff:10.1.2.3")))
        assertTrue(OutboundAddressPolicy.isPublic(ip("::ffff:8.8.8.8")))
        // NAT64
        assertFalse(OutboundAddressPolicy.isPublic(ip("64:ff9b::127.0.0.1")))
        assertFalse(OutboundAddressPolicy.isPublic(ip("64:ff9b::a9fe:a9fe")))
        assertTrue(OutboundAddressPolicy.isPublic(ip("64:ff9b::8.8.8.8")))
        // 6to4
        assertFalse(OutboundAddressPolicy.isPublic(ip("2002:7f00:1::1")))
        assertTrue(OutboundAddressPolicy.isPublic(ip("2002:808:808::1")))
        // IPv4-compatible (deprecated) is refused as a block
        assertFalse(OutboundAddressPolicy.isPublic(ip("::8.8.8.8")))
    }

    // ---------------------------------------------------------------- literal parsing

    @Test
    fun canonicalLiteralsParse() {
        assertEquals("127.0.0.1", ip("127.0.0.1").toString())
        assertEquals("0:0:0:0:0:ffff:7f00:1", ip("[::ffff:127.0.0.1]").toString())
        assertEquals(ip("::ffff:7f00:1"), ip("::ffff:127.0.0.1"))
        assertEquals(ip("2001:db8:0:0:0:0:0:1"), ip("2001:db8::1"))
        listOf("127.1", "0177.0.0.1", "2130706433", "1.2.3.256", "1:2:3:4:5:6:7:8:9", "1::2::3", "fe80::1%en0", "", "::g")
            .forEach { assertNull(IpAddress.parse(it), it) }
    }

    @Test
    fun legacyIpv4SpellingsDecodeAsTheResolverWould() {
        mapOf(
            "2130706433" to "127.0.0.1", "0x7f000001" to "127.0.0.1", "0x7f.1" to "127.0.0.1", "127.1" to "127.0.0.1",
            "0177.0.0.1" to "127.0.0.1", "127.0.1" to "127.0.0.1", "017700000001" to "127.0.0.1",
            "0xa9.0xfe.0xa9.0xfe" to "169.254.169.254", "3232235521" to "192.168.0.1",
        ).forEach { (spelling, address) -> assertEquals(ip(address), IpAddress.parseLegacyIpv4(spelling), spelling) }
        assertNull(IpAddress.parseLegacyIpv4("4294967296"))
        assertNull(IpAddress.parseLegacyIpv4("256.1"))
        assertNull(IpAddress.parseLegacyIpv4("example.com"))
    }

    @Test
    fun networksParseAndContain() {
        val n = IpNetwork.parse("100.64.0.0/10")
        assertTrue(ip("100.127.1.1") in n)
        assertFalse(ip("100.128.0.0") in n)
        assertFalse(ip("::ffff:100.64.0.1") in n)
        assertTrue(ip("10.1.2.3") in IpNetwork.parse("10.1.2.3"))
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("10.0.0.0/33") }
        assertFailsWith<IllegalArgumentException> { IpNetwork.parse("nope/8") }
    }

    // ---------------------------------------------------------------- admit

    @Test
    fun ipLiteralTargetsAreCheckedWithoutALookup() = runTest {
        val policy = OutboundAddressPolicy()
        assertEquals(listOf(ip("8.8.8.8")), policy.admit("https", "8.8.8.8", 443, noDns))
        assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", "127.0.0.1", 80, noDns) })
        assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", "[::1]", 80, noDns) })
        assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", "[::ffff:127.0.0.1]", 80, noDns) })
        assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", "169.254.169.254", 80, noDns) })
        assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", "[fe80::1%25en0]", 80, noDns) })
    }

    @Test
    fun alternativeIpv4SpellingsAreRefusedEvenWhenPublic() = runTest {
        val policy = OutboundAddressPolicy()
        listOf("2130706433", "0x7f.1", "127.1", "0177.0.0.1", "0x08.8.8.8", "134744072", "999.1.1.1", "1.2.3.4.5", "foo.123")
            .forEach { assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("http", it, 80, noDns) }, it) }
    }

    @Test
    fun aNameResolvingToAPrivateAddressIsRefused() = runTest {
        val resolver = TableResolver(mapOf(
            "loopback.example" to listOf("127.0.0.1"),
            "metadata.example" to listOf("169.254.169.254"),
            "intranet.example" to listOf("10.0.0.5"),
            "fakeip.example" to listOf("198.18.0.86"),
            "v6local.example" to listOf("fd00::1"),
            "mapped.example" to listOf("::ffff:192.168.1.1"),
            "public.example" to listOf("93.184.216.34", "2606:2800:220:1::1"),
        ))
        val policy = OutboundAddressPolicy()
        listOf("loopback.example", "metadata.example", "intranet.example", "fakeip.example", "v6local.example", "mapped.example")
            .forEach { assertEquals(TargetRejection.ADDRESS, rejection { policy.admit("https", it, 443, resolver) }, it) }
        assertEquals(2, policy.admit("https", "Public.Example.", 443, resolver).size)
    }

    @Test
    fun oneBlockedAnswerAmongPublicOnesRefusesTheTarget() = runTest {
        val resolver = TableResolver(mapOf("mixed.example" to listOf("93.184.216.34", "10.0.0.1")))
        assertEquals(TargetRejection.ADDRESS, rejection { OutboundAddressPolicy().admit("https", "mixed.example", 443, resolver) })
    }

    @Test
    fun unresolvableAndEmptyAnswersAreResolveFailures() = runTest {
        val resolver = TableResolver(mapOf("empty.example" to emptyList()))
        val policy = OutboundAddressPolicy()
        assertEquals(TargetRejection.RESOLVE, rejection { policy.admit("https", "nxdomain.example", 443, resolver) })
        assertEquals(TargetRejection.RESOLVE, rejection { policy.admit("https", "empty.example", 443, resolver) })
    }

    @Test
    fun schemeAndPortPolicy() = runTest {
        val resolver = TableResolver(mapOf("public.example" to listOf("93.184.216.34")))
        val policy = OutboundAddressPolicy()
        assertEquals(TargetRejection.SCHEME, rejection { policy.admit("ftp", "public.example", 21, resolver) })
        assertEquals(TargetRejection.SCHEME, rejection { policy.admit("file", "public.example", 80, resolver) })
        assertEquals(TargetRejection.PORT, rejection { policy.admit("http", "public.example", 8080, resolver) })
        assertEquals(TargetRejection.PORT, rejection { policy.admit("https", "public.example", 22, resolver) })
        // a refused port never triggers a lookup
        assertTrue(resolver.lookups.isEmpty())
        val wider = OutboundAddressPolicy(allowedPorts = setOf(80, 443, 8080))
        assertEquals(1, wider.admit("http", "public.example", 8080, resolver).size)
        assertFailsWith<IllegalArgumentException> { OutboundAddressPolicy(allowedPorts = setOf(0)) }
    }

    @Test
    fun allowlistsAreExplicitAndNarrow() = runTest {
        val resolver = TableResolver(mapOf(
            "stub.local.test" to listOf("127.0.0.1"),
            "other.local.test" to listOf("127.0.0.1"),
            "proxied.example" to listOf("198.18.0.86"),
        ))
        val strict = OutboundAddressPolicy()
        assertFalse(strict.isRelaxed)

        val hosts = OutboundAddressPolicy(allowedHosts = setOf("Stub.Local.Test"))
        assertTrue(hosts.isRelaxed)
        // an allowlisted host is admitted on any port and any address, still at the resolved address
        assertEquals(listOf(ip("127.0.0.1")), hosts.admit("http", "stub.local.test", 18299, resolver))
        assertEquals(TargetRejection.ADDRESS, rejection { hosts.admit("http", "other.local.test", 80, resolver) })

        val nets = OutboundAddressPolicy(allowedNetworks = listOf(IpNetwork.parse("198.18.0.0/15")))
        assertEquals(1, nets.admit("https", "proxied.example", 443, resolver).size)
        // ports are still checked for an allowed network
        assertEquals(TargetRejection.PORT, rejection { nets.admit("https", "proxied.example", 8443, resolver) })
        // and other special-purpose blocks stay closed
        assertEquals(TargetRejection.ADDRESS, rejection { nets.admit("http", "stub.local.test", 80, resolver) })
    }

    @Test
    fun configRefusesPolicyWithProxyAndClientsWithoutTheCapability() {
        val cfg = HttpClientConfig().apply {
            addressPolicy = OutboundAddressPolicy()
            proxyUrl = "http://127.0.0.1:7890"
        }
        assertTrue(cfg.validate().any { "addressPolicy" in it })

        val plain = object : HttpClient {
            override suspend fun request(request: HttpClientRequest): HttpClientResponse = error("unused")
            override fun stream(request: HttpClientRequest) = error("unused")
            override suspend fun close() = Unit
            override val capabilities: Set<HttpClientCapability> = setOf(HttpClientCapability.PROXY)
        }
        val e = assertFailsWith<HttpClientException> {
            HttpClient.createWith({ plain }) { addressPolicy = OutboundAddressPolicy() }
        }
        assertNotNull(e.message)
        assertTrue("ADDRESS_POLICY" in e.message!!)
    }
}
