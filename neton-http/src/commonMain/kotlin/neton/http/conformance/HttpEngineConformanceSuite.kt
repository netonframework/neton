package neton.http.conformance

import neton.core.http.adapter.HttpServerConfig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.core.http.HandlerArgs
import neton.core.http.HttpBodyWriter
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse

/**
 * 引擎一致性套件（spec zh-hans/spec/http-engine-capabilities.md §5）。
 *
 * 抽象层的价值 = 「多个引擎行为一致」的**可验证**程度。在这套东西存在之前，
 * 可插拔只是声称：Ktor CIO 有 `KtorLiveResponse` 这条真流式路径而 hyper4k 没有，
 * 这个差异此前只能靠读代码发现——正是套件要钉死的那类事。
 *
 * ## 测什么
 *
 * **不测** [neton.http.adapter.BufferedHttpDispatcher] 本身——那是三个引擎共用的
 * 一份代码，测它只会把同一段逻辑测三遍。真正会漂移的是每个 Adapter 的**翻译层**：
 * 引擎原生请求 → [BufferedHttpRequest]，以及 [BufferedHttpResponse] → 引擎响应。
 * header 大小写、多值 header、query 切分、空 body 与缺失 body、非 UTF-8 字节，
 * 每一处都是各写各的。
 *
 * ## 怎么接
 *
 * 每个 Adapter 仓实现 [roundTrip]：把 [ConformanceRequest] 转成自己的引擎类型，
 * 喂给自己的 dispatch，再把结果转回 [ConformanceResponse]。不需要真的开端口——
 * 端口测的是 socket，这里测的是翻译。
 */
public abstract class HttpEngineConformanceSuite {

    /** 被测适配器。实现方通常直接 `Hyper4kHttpAdapter(HttpServerConfig(port = 0), ...)`。 */
    public abstract fun createAdapter(): HttpAdapter

    /**
     * 把请求经由**引擎自己的翻译层**走一遍。
     *
     * 实现必须真的经过 `引擎请求类型 → BufferedHttpRequest` 这一跳；
     * 直接构造 `BufferedHttpRequest` 交给共享 dispatcher 会让本套件失去意义。
     */
    public abstract suspend fun roundTrip(request: ConformanceRequest): ConformanceResponse

    /**
     * 记录一次「因引擎不具备该能力而跳过」。
     *
     * **必须显式记录，不得静默通过**（spec §5.2）：一个能力全被跳过、报告却全绿的
     * 套件等于没有套件。实现方把它接到测试框架的 skip/ignore 机制或至少打印出来。
     */
    public abstract fun recordSkipped(capability: HttpCapability, testName: String)

    /**
     * 能力守卫：声明了就必须真跑，没声明就记为 skipped。
     *
     * 反过来的那一半同样重要——**声明了某能力却让对应测试跳过 = 构建失败**
     * （spec §5.2）。所以这里在声明时**不**允许跳过：走的是 [block]，
     * 它失败就是失败，这是防「声明先行、实现拖延」的唯一闸门。
     */
    protected suspend fun requiring(
        capability: HttpCapability,
        testName: String,
        block: suspend () -> Unit,
    ) {
        if (capability in createAdapter().capabilities) block() else recordSkipped(capability, testName)
    }

    /**
     * Runs a streaming producer through the engine's own transport and observes
     * when each chunk actually reaches the client.
     *
     * [roundTrip] cannot express this. It only has a request going in and a
     * response coming out, and a buffered engine returns exactly what a streaming
     * one returns: the difference lives in time, not in the value. Hence the extra
     * observation point, [ChunkMeter.released], which reports how many chunks the
     * transport has handed downstream. A buffering implementation keeps it at 0
     * for the whole production, which is the only reliable way to tell them apart.
     *
     * Implementations must stand up their real transport (start a server, connect
     * a client) rather than wrap a fake channel: a fake channel measures the
     * adapter's own bookkeeping, not whether it flushed.
     *
     * Only engines that declare [HttpCapability.STREAMING_RESPONSE] get here.
     */
    public open suspend fun streamRoundTrip(
        request: ConformanceRequest,
        produce: suspend (writer: HttpBodyWriter, meter: ChunkMeter) -> Unit,
    ): ConformanceStream = throw UnsupportedOperationException(
        "${createAdapter().adapterName()} declares STREAMING_RESPONSE but does not implement streamRoundTrip",
    )

    // -----------------------------------------------------------------------
    // The assertions. Implementations call them one by one from their own tests.
    // -----------------------------------------------------------------------

    /**
     * Repeated request headers.
     *
     * Multi-value headers are where engines diverge most: modelled as
     * `Map<String, String>` somewhere along the way, the second value simply
     * disappears, and a single-valued request never shows it.
     */
    public suspend fun checkRepeatedRequestHeadersSurvive() {
        val response = roundTrip(
            ConformanceRequest(
                method = "GET",
                path = ConformanceFixtures.ECHO,
                headers = mapOf("X-Multi" to listOf("one", "two")),
            ),
        )
        val seen = response.echoed("headers")
        expect(seen["X-Multi"] == listOf("one", "two")) {
            "repeated request header collapsed: ${seen["X-Multi"]}"
        }
    }

    /** The query must be split off the path, and multi-value params must keep every value. */
    public suspend fun checkQueryIsSplitFromPath() {
        val response = roundTrip(
            ConformanceRequest(method = "GET", path = ConformanceFixtures.ECHO, query = "tag=a&tag=b&page=2"),
        )
        val echoed = response.echoed("query")
        expect(echoed["tag"] == listOf("a", "b")) { "multi-value query param collapsed: ${echoed["tag"]}" }
        expect(echoed["page"] == listOf("2")) { "query param lost: ${echoed["page"]}" }
        expect(response.echoedText("path") == ConformanceFixtures.ECHO) {
            "query leaked into the path: ${response.echoedText("path")}"
        }
    }

    /**
     * A non-UTF-8 request body must arrive byte for byte.
     *
     * Any decode-to-string-and-back along the way rewrites these bytes into
     * replacement characters, and an ASCII test body never catches it.
     */
    public suspend fun checkNonUtf8BodyBytesSurvive() {
        val body = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x41)
        val response = roundTrip(
            ConformanceRequest(method = "POST", path = ConformanceFixtures.ECHO, body = body),
        )
        val echoed = response.echoedBytes("body")
        expect(echoed.contentEquals(body)) { "request body bytes were rewritten: $echoed" }
    }

    /** An empty body and an absent body must agree: length 0, not null. */
    public suspend fun checkEmptyBodyIsEmptyNotNull() {
        val response = roundTrip(ConformanceRequest(method = "GET", path = ConformanceFixtures.ECHO))
        expect(response.echoedBytes("body").isEmpty()) { "empty body did not arrive as empty" }
    }

    /**
     * Real streaming: the client must hold earlier chunks while production is
     * still going.
     *
     * This asserts only that chunk one reached the client before chunk two was
     * produced; it says nothing about latency. A buffering implementation cannot
     * pass it, because it writes nothing until production ends.
     */
    public suspend fun checkStreamingReleasesChunksAsProduced() {
        requiring(HttpCapability.STREAMING_RESPONSE, "checkStreamingReleasesChunksAsProduced") {
            var firstArrivedBeforeSecond = false
            val stream = streamRoundTrip(
                ConformanceRequest(method = "GET", path = ConformanceFixtures.STREAM),
            ) { writer, meter ->
                writer.writeChunk("chunk-1")
                firstArrivedBeforeSecond = meter.awaitReleased(1)
                writer.writeChunk("chunk-2")
            }

            expect(firstArrivedBeforeSecond) {
                "response was buffered: no chunk reached the client before the last one was produced"
            }
            expect(stream.chunks.size == 2) { "expected 2 chunks downstream, got ${stream.chunks.size}" }
            expect(stream.chunks[0].decodeToString() == "chunk-1") { "chunk order changed" }
            expect(stream.status == 200) { "streaming response status was ${stream.status}" }
        }
    }

    /** A streaming response must not declare Content-Length: the transport frames it. */
    public suspend fun checkStreamingDoesNotDeclareContentLength() {
        requiring(HttpCapability.STREAMING_RESPONSE, "checkStreamingDoesNotDeclareContentLength") {
            val stream = streamRoundTrip(
                ConformanceRequest(method = "GET", path = ConformanceFixtures.STREAM),
            ) { writer, _ -> writer.writeChunk("only") }

            val declared = stream.headers.keys.firstOrNull { it.equals("Content-Length", ignoreCase = true) }
            expect(declared == null) { "streaming response declared Content-Length" }
        }
    }
}

/** Throws [AssertionError] so every test framework reports the failure as its own. */
private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

/**
 * How many chunks the transport has handed downstream.
 *
 * [awaitReleased] rather than a bare sample, because a write returning does not mean
 * the client received anything: the engine may still be holding the chunk in its own
 * buffer. Sampling races the transport and passes or fails on timing. Waiting is
 * decisive in both directions: a streaming engine satisfies it almost immediately,
 * and a buffering one can never satisfy it, because it writes nothing until the
 * producer returns.
 */
public interface ChunkMeter {
    /** Chunks delivered so far, without waiting. */
    public fun released(): Int

    /** Waits until at least [count] chunks reached the client. False on timeout. */
    public suspend fun awaitReleased(count: Int, timeoutMillis: Long = 2_000): Boolean
}

/** What one streaming round trip observed. */
public class ConformanceStream(
    public val status: Int,
    public val headers: Map<String, List<String>> = emptyMap(),
    /** The chunks the client actually received, in arrival order. */
    public val chunks: List<ByteArray> = emptyList(),
)

/**
 * Fixture routes shipped with the suite. Implementations mount [routes] in their
 * test context so the assertions have something to hit.
 *
 * The suite owns them rather than each repo writing its own: the assertions
 * depend on exactly what the handler echoes back.
 */
public object ConformanceFixtures {
    public const val ECHO: String = "/conformance/echo"
    public const val STREAM: String = "/conformance/stream"

    /** Echoes what the handler saw, for the translation-layer assertions to compare. */
    public val routes: List<RouteDefinition> = listOf(
        echoRoute(HttpMethod.GET),
        echoRoute(HttpMethod.POST),
    )

    private fun echoRoute(method: HttpMethod) = RouteDefinition(
        pattern = ECHO,
        method = method,
        allowAnonymous = true,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any = mapOf(
                "path" to context.request.path,
                "query" to context.request.queryParams.toMap(),
                "headers" to context.request.headers.toMap(),
                // Bytes echo back as integers: JSON cannot carry arbitrary bytes, and
                // this assertion is precisely about being re-encoded as text on the way.
                "body" to context.request.body().map { it.toInt() and 0xFF },
            )
        },
    )
}

private fun ConformanceResponse.envelopeData(): JsonObject {
    val root = Json.parseToJsonElement(body.decodeToString()).jsonObject
    return root["data"]?.jsonObject
        ?: throw AssertionError("response envelope has no data: ${body.decodeToString()}")
}

private fun ConformanceResponse.echoed(field: String): Map<String, List<String>> =
    envelopeData().getValue(field).jsonObject.mapValues { (_, values) ->
        values.jsonArray.map { it.jsonPrimitive.content }
    }

private fun ConformanceResponse.echoedText(field: String): String =
    envelopeData().getValue(field).jsonPrimitive.content

private fun ConformanceResponse.echoedBytes(field: String): ByteArray =
    envelopeData().getValue(field).jsonArray
        .map { it.jsonPrimitive.content.toInt().toByte() }
        .toByteArray()

/**
 * 引擎无关的请求描述。
 *
 * `headers` 用 `Map<String, List<String>>` 而不是 `Map<String, String>`：
 * 多值 header（`Set-Cookie`、`Accept`）正是各引擎最容易各写各的地方，
 * 单值模型会把这个差异从测试里抹掉。
 */
public class ConformanceRequest(
    public val method: String,
    public val path: String,
    public val query: String = "",
    public val headers: Map<String, List<String>> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
)

public class ConformanceResponse(
    public val status: Int,
    public val headers: Map<String, List<String>> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
)

/** 套件内部用的转换，供实现方复用，省得每个仓再写一遍。 */
public fun ConformanceRequest.toBuffered(): BufferedHttpRequest = BufferedHttpRequest(
    method = method,
    path = path,
    query = query,
    headers = headers,
    body = body,
)

public fun BufferedHttpResponse.toConformance(): ConformanceResponse = ConformanceResponse(
    status = status,
    headers = headers,
    body = body,
)
