package neton.http.ktor

import neton.core.component.NetonContext
import neton.core.http.adapter.HttpServerConfig
import neton.core.http.adapter.TlsSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Ktor adapter does not terminate TLS. A TLS config must stop it starting —
 * serving cleartext on a listener the application believes is encrypted would be
 * a silent downgrade.
 */
class KtorTlsRefusalTest {
    @Test
    fun startingWithTlsConfiguredFailsRatherThanServingCleartext() = runBlocking {
        val adapter = KtorHttpAdapter(
            HttpServerConfig(
                port = 0,
                tls = TlsSettings("/certs/server.crt", "/certs/server.key"),
            ),
        )
        val e = assertFailsWith<IllegalStateException> {
            adapter.start(NetonContext(emptyArray()), null)
        }
        assertTrue(
            e.message?.contains("does not support TLS") == true,
            "the failure must name the reason: ${e.message}",
        )
    }
}
