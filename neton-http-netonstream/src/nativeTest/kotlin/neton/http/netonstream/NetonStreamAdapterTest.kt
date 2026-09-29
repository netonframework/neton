package neton.http.netonstream

import neton.core.Neton
import neton.core.http.adapter.HttpAdapterFactory
import neton.core.http.adapter.HttpCapability
import neton.core.http.adapter.HttpCapabilityException
import neton.core.http.adapter.HttpCapabilityRequirements
import neton.core.http.adapter.HttpServerConfig
import neton.core.http.adapter.validateHttpCapabilities
import neton.http.http
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetonStreamAdapterTest {

    @Test
    fun constructorCanBePassedToHttpDsl() {
        // The engine is selected explicitly; this module deliberately has no no-argument `http { }`.
        Neton.LaunchBuilder().http(::NetonStreamHttpAdapter)
    }

    @Test
    fun createsConfiguredAdapterWithHyper4kCapabilities() {
        val factory: HttpAdapterFactory = ::NetonStreamHttpAdapter
        val adapter = factory(HttpServerConfig(port = 8123))
        assertIs<NetonStreamHttpAdapter>(adapter)
        assertEquals("NetonStream", adapter.adapterName())
        assertEquals(8123, adapter.port())
        assertEquals(
            setOf(HttpCapability.ASYNC_HANDOFF, HttpCapability.STREAMING_RESPONSE, HttpCapability.HTTP_2),
            adapter.capabilities,
        )
    }

    @Test
    fun capabilityValidationPassesForWhatIsDeclared() {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0))
        val required = HttpCapabilityRequirements().apply {
            require(HttpCapability.STREAMING_RESPONSE, "sse")
            require(HttpCapability.HTTP_2, "grpc-web")
            require(HttpCapability.ASYNC_HANDOFF, "jobs")
        }
        validateHttpCapabilities(adapter.adapterName(), adapter.capabilities, required)
    }

    @Test
    fun capabilityValidationRejectsWhatIsNotDeclared() {
        val adapter = NetonStreamHttpAdapter(HttpServerConfig(port = 0))
        for (missing in listOf(HttpCapability.TRAILERS, HttpCapability.MULTIPART)) {
            val required = HttpCapabilityRequirements().apply { require(missing, "component-x") }
            val error = assertFailsWith<HttpCapabilityException> {
                validateHttpCapabilities(adapter.adapterName(), adapter.capabilities, required)
            }
            assertTrue(error.message!!.contains("NetonStream"))
            assertTrue(error.message!!.contains(missing.name))
            assertTrue(error.message!!.contains("component-x"))
        }
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { NetonStreamHttpAdapter(HttpServerConfig(port = 0, maxConnections = 0)) }
        assertFailsWith<IllegalArgumentException> { NetonStreamOptions(reactors = 0) }
        assertFailsWith<IllegalArgumentException> { NetonStreamOptions(maxRequestBodyBytes = 0) }
    }

    @Test
    fun defaultsMatchHyper4k() {
        val options = NetonStreamOptions()
        assertEquals(16L * 1024 * 1024, options.maxRequestBodyBytes)
        assertEquals("0.0.0.0", options.host)
        assertTrue(options.reactors >= 1)
    }

    // --- gzip policy (hyper4k's maybeCompress, case by case) -------------------------------------

    private val big = ("{\"k\":\"" + "v".repeat(1000) + "\"}").encodeToByteArray()
    private fun json(headers: Map<String, List<String>> = emptyMap(), status: Int = 200, body: ByteArray = big) =
        EngineResponse(status, mapOf("Content-Type" to listOf("application/json")) + headers, body)

    @Test
    fun gzipsACompressibleBodyForAClientThatAcceptsIt() {
        val out = maybeCompress(true, "gzip, deflate, br", json())
        assertEquals(listOf("gzip"), out.headers["Content-Encoding"])
        assertEquals(listOf("Accept-Encoding"), out.headers["Vary"])
        assertTrue(out.body.size < big.size)
        assertEquals(0x1f, out.body[0].toInt() and 0xff)
        assertEquals(0x8b, out.body[1].toInt() and 0xff)
    }

    @Test
    fun gzipPolicyLeavesTheResponseAloneWhenItShould() {
        val r = json()
        assertSame(r, maybeCompress(false, "gzip", r), "compression disabled")
        assertSame(r, maybeCompress(true, null, r), "no Accept-Encoding")
        assertSame(r, maybeCompress(true, "br", r), "gzip not offered")
        assertSame(r, maybeCompress(true, "gzip;q=0", r), "gzip refused with q=0")
        val small = json(body = ByteArray(255) { 'a'.code.toByte() })
        assertSame(small, maybeCompress(true, "gzip", small), "under 256 bytes")
        val partial = json(status = 206)
        assertSame(partial, maybeCompress(true, "gzip", partial), "206")
        val encoded = json(mapOf("Content-Encoding" to listOf("br")))
        assertSame(encoded, maybeCompress(true, "gzip", encoded), "already encoded")
        val noTransform = json(mapOf("Cache-Control" to listOf("public, no-transform")))
        assertSame(noTransform, maybeCompress(true, "gzip", noTransform), "no-transform")
        val binary = EngineResponse(200, mapOf("Content-Type" to listOf("image/png")), big)
        assertSame(binary, maybeCompress(true, "gzip", binary), "not a compressible type")
        val untyped = EngineResponse(200, emptyMap(), big)
        assertSame(untyped, maybeCompress(true, "gzip", untyped), "no Content-Type")
        val random = kotlin.random.Random(7).nextBytes(2048)
        val incompressible = EngineResponse(200, mapOf("Content-Type" to listOf("text/plain")), random)
        assertSame(incompressible, maybeCompress(true, "gzip", incompressible), "gzip would not be smaller")
        val streamed = EngineResponse(200, mapOf("Content-Type" to listOf("text/event-stream")), big, LiveBody(1))
        assertSame(streamed, maybeCompress(true, "gzip", streamed), "streamed")
    }

    @Test
    fun gzipMergesVaryAndDropsContentLength() {
        val out = maybeCompress(
            true, "*",
            json(mapOf("Vary" to listOf("Origin"), "Content-Length" to listOf(big.size.toString()))),
        )
        assertEquals(listOf("Origin", "Accept-Encoding"), out.headers["Vary"])
        assertNull(out.headers["Content-Length"])
        val already = maybeCompress(true, "gzip", json(mapOf("Vary" to listOf("Accept-Encoding"))))
        assertEquals(listOf("Accept-Encoding"), already.headers["Vary"])
    }

    @Test
    fun compressibleTypesAreHyper4ks() {
        for (type in listOf("text/html; charset=utf-8", "application/json", "application/problem+json", "application/javascript", "application/xml", "image/svg+xml")) {
            val r = EngineResponse(200, mapOf("Content-Type" to listOf(type)), big)
            assertEquals(listOf("gzip"), maybeCompress(true, "gzip", r).headers["Content-Encoding"], type)
        }
    }

    @Test
    fun acceptEncodingParsing() {
        assertTrue(acceptsGzip("gzip"))
        assertTrue(acceptsGzip("GZIP;q=0.5"))
        assertTrue(acceptsGzip("br, *;q=0.1"))
        assertFalse(acceptsGzip("gzip;q=0"))
        assertFalse(acceptsGzip("identity"))
        assertFalse(acceptsGzip(""))
        assertFalse(acceptsGzip(null))
    }
}
