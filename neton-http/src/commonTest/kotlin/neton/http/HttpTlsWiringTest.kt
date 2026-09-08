package neton.http

import neton.core.component.HttpConfig
import neton.core.component.tls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The `http { tls { } }` DSL populates HttpConfig.tls. (The application.conf
 * override path is exercised through HttpComponent in integration; this pins the
 * DSL contract, which is the part a user writes.)
 */
class HttpTlsWiringTest {

    @Test
    fun tlsDslPopulatesSettings() {
        val cfg = HttpConfig().apply {
            tls {
                certificatePath = "/certs/server.crt"
                privateKeyPath = "/certs/server.key"
                alpnProtocols = listOf("h2", "http/1.1")
            }
        }
        val t = cfg.tls
        assertEquals("/certs/server.crt", t?.certificatePath)
        assertEquals("/certs/server.key", t?.privateKeyPath)
        assertEquals(listOf("h2", "http/1.1"), t?.alpnProtocols)
    }

    @Test
    fun alpnDefaultsToHttp1() {
        val cfg = HttpConfig().apply {
            tls { certificatePath = "/c"; privateKeyPath = "/k" }
        }
        assertEquals(listOf("http/1.1"), cfg.tls?.alpnProtocols)
    }

    @Test
    fun tlsBlockWithoutCertFails() {
        assertFailsWith<IllegalArgumentException> {
            HttpConfig().apply { tls { privateKeyPath = "/k" } }
        }
    }

    @Test
    fun noTlsBlockLeavesItNull() {
        assertNull(HttpConfig().tls)
    }
}
