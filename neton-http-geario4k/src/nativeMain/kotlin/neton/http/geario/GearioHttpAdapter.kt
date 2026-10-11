package neton.http.geario

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.core.http.adapter.HttpServerConfig
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse
import neton.http.geario.cinterop.GearioHttpNativeRequest as CRequest
import neton.http.geario.cinterop.geario_http_native_h1_respond
import neton.http.geario.cinterop.geario_http_native_h1_start
import neton.http.geario.cinterop.geario_http_native_h1_stop

/** Neton adapter for geario-http's native H1 path. Experimental and explicit. */
@OptIn(ExperimentalForeignApi::class)
public class GearioHttpAdapter(
    private val serverConfig: HttpServerConfig,
) : HttpAdapter {
    private val dispatcher = BufferedHttpDispatcher(serverConfig)
    private var server: CPointer<*>? = null
    private var stateRef: StableRef<NativeState>? = null

    override val capabilities: Set<HttpCapability> = emptySet()
    override fun port(): Int = serverConfig.port
    override fun adapterName(): String = "GearioHttp"

    override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) {
        check(server == null) { "geario-http server already started" }
        dispatcher.bind(ctx)
        val state = NativeState(dispatcher)
        val ref = StableRef.create(state)
        val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val running = geario_http_native_h1_start(
            host = "0.0.0.0",
            port = serverConfig.port.toUShort(),
            on_request = staticCFunction(::onRequest),
            user_data = ref.asCPointer(),
        )
        if (running == null) {
            ref.dispose()
            error("geario-http native H1 failed to start on port ${serverConfig.port}")
        }
        server = running
        stateRef = ref
        onStarted?.invoke(kotlin.time.Clock.System.now().toEpochMilliseconds() - startedAt)
        while (server != null) delay(250)
    }

    override suspend fun stop() {
        val running = server ?: return
        server = null
        geario_http_native_h1_stop(running.reinterpret())
        stateRef?.dispose()
        stateRef = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private class NativeState(private val dispatcher: BufferedHttpDispatcher) {
    fun submit(source: CPointer<CRequest>) {
        val request = source.pointed
        val responder = request.responder
        val buffered = BufferedHttpRequest(
            method = request.method.copyToString(),
            path = request.path.copyToString(),
            query = request.query.copyToString(),
            rawHeaders = request.headers.copyToByteArray(),
            body = request.body.copyToByteArray(),
        )
        // Baseline handlers normally complete without suspension. Starting the
        // state machine directly avoids allocating a Job and SupervisorJob for
        // every request while retaining asynchronous continuation semantics when
        // a handler actually suspends.
        val completion = object : Continuation<BufferedHttpResponse> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<BufferedHttpResponse>) {
                val response = result.getOrElse {
                    dispatcher.transportFailureResponse(500, "Internal Server Error")
                }
                respond(responder, response)
            }
        }
        val operation: suspend () -> BufferedHttpResponse = {
            dispatcher.dispatch(buffered)
        }
        operation.startCoroutine(completion)
    }

    private fun respond(responder: ULong, response: BufferedHttpResponse) {
        val headers = encodeHeaders(response.headers)
        headers.usePinned { hp ->
            response.body.usePinned { bp ->
                geario_http_native_h1_respond(
                    responder = responder,
                    status = response.status.toUShort(),
                    headers = if (headers.isEmpty()) null else hp.addressOf(0).reinterpret(),
                    headers_len = headers.size.convert(),
                    body = if (response.body.isEmpty()) null else bp.addressOf(0).reinterpret(),
                    body_len = response.body.size.convert(),
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun onRequest(userData: COpaquePointer?, request: CPointer<CRequest>?) {
    if (userData == null || request == null) return
    runCatching { userData.asStableRef<NativeState>().get().submit(request) }
}

@OptIn(ExperimentalForeignApi::class)
private fun neton.http.geario.cinterop.GearioHttpSlice.copyToByteArray(): ByteArray =
    if (ptr == null || len == 0UL) ByteArray(0) else ptr!!.readBytes(len.toInt())

@OptIn(ExperimentalForeignApi::class)
private fun neton.http.geario.cinterop.GearioHttpSlice.copyToString(): String = copyToByteArray().decodeToString()

private fun encodeHeaders(headers: Map<String, List<String>>): ByteArray {
    if (headers.isEmpty()) return ByteArray(0)

    // Build one UTF-8 payload instead of allocating a line String, an ArrayList,
    // and a second joined String for every response.
    val builder = StringBuilder()
    var first = true
    for ((name, values) in headers) {
        for (value in values) {
            if (!first) builder.append('\n')
            builder.append(name).append(": ").append(value)
            first = false
        }
    }
    return if (first) ByteArray(0) else builder.toString().encodeToByteArray()
}
