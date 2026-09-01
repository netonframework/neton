package neton.http.adapter

import neton.core.http.adapter.HttpServerConfig

import kotlinx.coroutines.runBlocking
import neton.core.component.NetonContext
import neton.core.http.ApiEnvelope
import neton.core.http.Cookie
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.HttpResponse
import neton.core.http.HttpStatus
import neton.core.http.MutableHeaders
import neton.core.http.SimpleCookie
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufferedHttpDispatcherContractTest {
    @Test
    fun preservesRepeatedRequestAndResponseHeaders() = runBlocking {
        val route = RouteDefinition(
            pattern = "/headers",
            method = HttpMethod.GET,
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(
                    context: HttpContext,
                    args: neton.core.http.HandlerArgs,
                ): Any {
                    assertEquals(listOf("one", "two"), context.request.headers.getAll("X-Test"))
                    context.response.cookie(SimpleCookie("first", "1"))
                    context.response.cookie(SimpleCookie("second", "2"))
                    context.response.write("ok".encodeToByteArray())
                    return Unit
                }
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedRequestEngine(listOf(route)))
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }

        val response = dispatcher.dispatch(
            BufferedHttpRequest(
                method = "GET",
                path = "/headers",
                query = "",
                headers = mapOf("X-Test" to listOf("one", "two")),
                body = ByteArray(0),
            ),
        )

        assertEquals(listOf("first=1", "second=2"), response.headers["Set-Cookie"])
        assertEquals("ok", response.body.decodeToString())
    }

    @Test
    fun fastEnvelopeIsByteIdenticalToKotlinx() {
        val samples: List<Any?> = listOf(
            null,
            "Hello, World!",
            "escape: \" \\ \n \r \t \b \u000C \u0001 中文",
            1,
            42L,
            2.5,
            true,
            false,
            mapOf("message" to "Hello, World!"),
            mapOf("a" to 1, "b" to listOf("x", null, mapOf("y" to 2.5)), "c" to true),
            listOf(1, "two", false, null, mapOf("k" to "v")),
            emptyMap<String, Any?>(),
            emptyList<Any?>(),
        )
        for (sample in samples) {
            val fast = BufferedHttpDispatcher.fastEnvelopeBody(0, "OK", sample)
                ?: error("fast path rejected supported shape: $sample")
            val expected = BufferedHttpDispatcher.envelopeJson.encodeToString(
                ApiEnvelope.serializer(),
                ApiEnvelope.ok(BufferedHttpDispatcher.valueToJsonElement(sample)),
            )
            assertEquals(expected, fast.decodeToString(), "mismatch for $sample")
        }
    }

    @Test
    fun canonicalAndNonCanonicalPathsProduceSameResponse() = runBlocking {
        val route = RouteDefinition(
            pattern = "/json",
            method = HttpMethod.GET,
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(
                    context: HttpContext,
                    args: neton.core.http.HandlerArgs,
                ): Any = mapOf("message" to "Hello, World!")
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedRequestEngine(listOf(route)))
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }

        val bodies = listOf("/json", "/json/", "//json").map { path ->
            dispatcher.dispatch(
                BufferedHttpRequest("GET", path, "", emptyMap(), ByteArray(0)),
            ).body.decodeToString()
        }
        assertEquals(1, bodies.toSet().size)
        assertEquals("""{"code":0,"message":"OK","data":{"message":"Hello, World!"}}""", bodies[0])
    }

    @Test
    fun multipartUploadFilesAndFormFieldsParseFromBytes() = runBlocking {
        val route = RouteDefinition(
            pattern = "/upload",
            method = HttpMethod.POST,
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(
                    context: HttpContext,
                    args: neton.core.http.HandlerArgs,
                ): Any {
                    val files = context.request.uploadFiles().all()
                    val form = context.request.form()
                    assertEquals(2, files.size)
                    val quoted = files.first { it.filename == "a.txt" }!!
                    assertEquals("file", quoted.fieldName)
                    assertEquals("text/plain", quoted.contentType)
                    assertEquals("FILEDATA", quoted.bytes().decodeToString())
                    // RFC 5987 filename*：部分客户端对非 ASCII 名只发这个形态。
                    val star = files.first { it.filename == "图.jpg" }!!
                    assertEquals("FILE2", star.bytes().decodeToString())
                    assertEquals("value1", form.get("field1"))
                    return mapOf("files" to files.size)
                }
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedRequestEngine(listOf(route)))
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }

        val body = (
            "--B\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "\r\n" +
                "value1\r\n" +
                "--B\r\n" +
                "Content-Disposition: form-data; name=file; filename=\"a.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "FILEDATA\r\n" +
                "--B\r\n" +
                "Content-Disposition: form-data; name=pic; filename*=UTF-8''%E5%9B%BE.jpg\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "\r\n" +
                "FILE2\r\n" +
                "--B--\r\n"
            ).encodeToByteArray()

        val response = dispatcher.dispatch(
            BufferedHttpRequest(
                method = "POST",
                path = "/upload",
                query = "",
                headers = mapOf("Content-Type" to listOf("multipart/form-data; boundary=B")),
                body = body,
            ),
        )
        assertEquals(200, response.status)
        assertEquals("""{"code":0,"message":"OK","data":{"files":2}}""", response.body.decodeToString())
    }

    @Test
    fun liveResponseCommitIsNotRewrittenByTransport() = runBlocking {
        val route = RouteDefinition(
            pattern = "/stream",
            method = HttpMethod.GET,
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(
                    context: HttpContext,
                    args: neton.core.http.HandlerArgs,
                ): Any {
                    context.response.contentType = "text/event-stream"
                    context.response.stream {
                        writeChunk("chunk1")
                        writeChunk("chunk2")
                    }
                    return Unit
                }
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedRequestEngine(listOf(route)))
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }

        val live = RecordingLiveResponse()
        val result = dispatcher.dispatch(
            BufferedHttpRequest("GET", "/stream", "", emptyMap(), ByteArray(0)),
            liveResponse = live,
        )

        assertTrue(live.isCommitted)
        assertEquals("chunk1chunk2", live.streamedText)
        assertTrue(result.streamed)
        assertEquals(0, result.body.size)
        assertEquals(200, result.status)
        assertEquals(12L, live.bytesOut)
    }

    @Test
    fun uncommittedLiveResponseFallsBackToEnvelope() = runBlocking {
        val route = RouteDefinition(
            pattern = "/json",
            method = HttpMethod.GET,
            allowAnonymous = true,
            handler = object : RouteHandler {
                override suspend fun invoke(
                    context: HttpContext,
                    args: neton.core.http.HandlerArgs,
                ): Any = mapOf("message" to "Hello, World!")
            },
        )
        val context = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, FixedRequestEngine(listOf(route)))
        }
        val dispatcher = BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(context) }

        val live = RecordingLiveResponse()
        val result = dispatcher.dispatch(
            BufferedHttpRequest("GET", "/json", "", emptyMap(), ByteArray(0)),
            liveResponse = live,
        )
        assertFalse(live.isCommitted)
        assertFalse(result.streamed)
        assertEquals("""{"code":0,"message":"OK","data":{"message":"Hello, World!"}}""", result.body.decodeToString())
    }
}

private class FixedRequestEngine(
    private val routes: List<RouteDefinition>,
) : RequestEngine {
    override fun registerRoute(route: RouteDefinition) = Unit
    override fun getRoutes(): List<RouteDefinition> = routes
}

/** 最小 live transport 替身：记录 write / stream 字节，模拟 Ktor 真流式响应。 */
private class RecordingLiveResponse : HttpResponse {
    override var status: HttpStatus = HttpStatus.OK
    override val headers: MutableHeaders = RecordingMutableHeaders()
    override fun cookie(cookie: Cookie) {}

    private var _committed = false
    override val isCommitted: Boolean get() = _committed

    private val sink = StringBuilder()
    val streamedText: String get() = sink.toString()
    private var written: Long = 0L
    override val bytesOut: Long get() = written

    override suspend fun write(data: ByteArray) {
        check(!_committed) { "already committed" }
        _committed = true
        written = data.size.toLong()
        sink.append(data.decodeToString())
    }

    override suspend fun stream(block: suspend neton.core.http.HttpBodyWriter.() -> Unit) {
        check(!_committed) { "already committed" }
        _committed = true
        val writer = object : neton.core.http.HttpBodyWriter {
            override suspend fun writeChunk(chunk: ByteArray) {
                sink.append(chunk.decodeToString())
                written += chunk.size
            }
        }
        writer.block()
    }
}

private class RecordingMutableHeaders : MutableHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()
    override fun get(name: String): String? = map.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    override fun getAll(name: String): List<String> = map.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
    override fun contains(name: String): Boolean = map.keys.any { it.equals(name, true) }
    override fun names(): Set<String> = map.keys
    override fun toMap(): Map<String, List<String>> = map.mapValues { it.value.toList() }
    override fun set(name: String, value: String) {
        map.keys.filter { it.equals(name, true) }.forEach(map::remove)
        map[name] = mutableListOf(value)
    }
    override fun add(name: String, value: String) {
        map.getOrPut(map.keys.firstOrNull { it.equals(name, true) } ?: name) { mutableListOf() }.add(value)
    }
    override fun remove(name: String) {
        map.keys.filter { it.equals(name, true) }.forEach(map::remove)
    }
    override fun clear() = map.clear()
}
