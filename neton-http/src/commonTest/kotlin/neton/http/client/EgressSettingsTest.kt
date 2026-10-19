package neton.http.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EgressSettingsTest {

    @Test
    fun absentSectionIsTheStrictDefault() {
        for (conf in listOf(null, emptyMap(), mapOf("server" to mapOf("port" to 8080)))) {
            val s = EgressSettings.fromApplicationConfig(conf)
            assertEquals(OutboundAddressPolicy.DEFAULT_PORTS, s.allowedPorts)
            assertTrue(s.allowedHosts.isEmpty())
            assertTrue(s.allowedNetworks.isEmpty())
            assertFalse(s.isRelaxed)
        }
    }

    @Test
    fun sectionIsParsedIntoOnePolicyForHttpAndSmtp() {
        val s = EgressSettings.fromApplicationConfig(mapOf(
            "egress" to mapOf(
                "allowed_ports" to "80, 443,8443",
                "allowed_hosts" to " Model.Internal. ,127.0.0.1",
                "allowed_networks" to "10.20.0.0/16, 198.18.0.0/15",
            ),
        ))
        assertEquals(setOf(80, 443, 8443), s.allowedPorts)
        assertEquals(setOf("model.internal", "127.0.0.1"), s.policy.allowedHosts)
        assertEquals(listOf(IpNetwork.parse("10.20.0.0/16"), IpNetwork.parse("198.18.0.0/15")), s.allowedNetworks)
        assertTrue(s.isRelaxed)
        // SMTP shares the allowlist but checks mail ports.
        assertEquals(EgressSettings.SMTP_PORTS, s.smtpPolicy.allowedPorts)
        assertEquals(s.policy.allowedHosts, s.smtpPolicy.allowedHosts)
        assertEquals(s.allowedNetworks, s.smtpPolicy.allowedNetworks)
        assertTrue("allowed_networks=10.20.0.0/16,198.18.0.0/15" in s.describe(), s.describe())
    }

    @Test
    fun malformedEntriesFailInsteadOfFallingBack() {
        val badPort = assertFailsWith<IllegalArgumentException> {
            EgressSettings.fromApplicationConfig(mapOf("egress" to mapOf("allowed_ports" to "443,http")))
        }
        assertTrue("egress.allowed_ports" in badPort.message!!)
        val badNet = assertFailsWith<IllegalArgumentException> {
            EgressSettings.fromApplicationConfig(mapOf("egress" to mapOf("allowed_networks" to "10.0.0.0/33")))
        }
        assertTrue("egress.allowed_networks" in badNet.message!!)
    }

    @Test
    fun guardedClientsAlwaysCarryTheOperatorPolicy() {
        val settings = EgressSettings(allowedNetworks = listOf(IpNetwork.parse("10.20.0.0/16")))
        val seen = mutableListOf<HttpClientConfig>()
        val provider = GuardedHttpClientProvider(settings) { block ->
            seen += HttpClientConfig().apply(block)
            object : HttpClient {
                override suspend fun request(request: HttpClientRequest): HttpClientResponse = error("unused")
                override fun stream(request: HttpClientRequest) = error("unused")
                override suspend fun close() = Unit
                override val capabilities: Set<HttpClientCapability> = emptySet()
            }
        }
        provider.create { proxyUrl = "http://proxy.example:3128"; connectMillis = 1_000 }
        val cfg = seen.single()
        assertSame(settings.policy, cfg.addressPolicy)
        assertEquals("http://proxy.example:3128", cfg.proxyUrl)
        assertEquals(1_000, cfg.connectMillis)

        val e = assertFailsWith<IllegalArgumentException> {
            provider.create { addressPolicy = OutboundAddressPolicy(allowedHosts = setOf("anything")) }
        }
        assertTrue("operator egress policy" in e.message!!)
    }
}
