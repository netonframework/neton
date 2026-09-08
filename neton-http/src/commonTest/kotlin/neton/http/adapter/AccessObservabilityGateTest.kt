package neton.http.adapter

import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.AccessLogEntry
import neton.core.interfaces.AccessLogWriter
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.http.adapter.HttpServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-request timestamps and access record are skipped when nothing consumes
 * them. This must be behaviour-preserving: when a writer *is* present the record
 * still arrives with a real latency, and the response is identical either way.
 */
class AccessObservabilityGateTest {

    private val okRoute = RouteDefinition(
        pattern = "/x",
        method = HttpMethod.GET,
        allowAnonymous = true,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
                context.response.write("ok".encodeToByteArray())
                return Unit
            }
        },
    )

    private fun request() = BufferedHttpRequest("GET", "/x", "", emptyMap(), ByteArray(0))

    @Test
    fun withNoWriterTheResponseIsUnchanged() = runBlocking {
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedEngine(listOf(okRoute)))
        }
        val d = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
        val r = d.dispatch(request())
        assertEquals(200, r.status)
        assertEquals("ok", r.body.decodeToString())
    }

    @Test
    fun anAccessLogWriterStillReceivesAnEntryWithLatency() = runBlocking {
        val received = CompletableDeferred<AccessLogEntry>()
        val writer = object : AccessLogWriter {
            override suspend fun write(entry: AccessLogEntry) { received.complete(entry) }
        }
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedEngine(listOf(okRoute)))
            bind(AccessLogWriter::class, writer)
        }
        val d = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
        val r = d.dispatch(request())
        assertEquals("ok", r.body.decodeToString())
        // The record is written on a background scope; await it rather than racing it.
        val e = withTimeout(5_000) { received.await() }
        assertEquals("/x", e.requestUrl)
        assertTrue(e.endTime >= e.beginTime, "latency must be non-negative when observed")
    }

    private class FixedEngine(private val routes: List<RouteDefinition>) : RequestEngine {
        override fun registerRoute(route: RouteDefinition) = Unit
        override fun getRoutes(): List<RouteDefinition> = routes
    }
}
