package neton.http.adapter

import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler
import neton.core.http.adapter.HttpServerConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Scanning query lookups must agree with the old map-based parse on every shape:
 * single value, repeated key, missing, empty value, percent/plus encoding, and a
 * key that is a prefix of another. Both `request.queryParam` and `args.first/all`
 * are exercised end to end.
 */
class QueryScanTest {
    private fun echoHandler(readAll: Boolean = false) = object : RouteHandler {
        override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any {
            val out = if (readAll) (args.all("k")?.joinToString(",") ?: "null")
                      else (context.request.queryParam("k") ?: "null")
            context.response.write(out.encodeToByteArray()); return Unit
        }
    }
    private fun d(readAll: Boolean = false): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray()).apply {
            bind(RequestEngine::class, object : RequestEngine {
                override fun registerRoute(route: RouteDefinition) = Unit
                override fun getRoutes() = listOf(RouteDefinition("/e", HttpMethod.GET, allowAnonymous = true, handler = echoHandler(readAll)))
            })
        }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }
    private suspend fun get(disp: BufferedHttpDispatcher, query: String) =
        disp.dispatch(BufferedHttpRequest("GET", "/e", query, emptyMap(), ByteArray(0))).body.decodeToString()

    // Cross-check scan vs the reference map parse.
    private fun refFirst(q: String, name: String) =
        BufferedHttpDispatcher.parseParameters(q)[name]?.firstOrNull()

    @Test fun singleValue() = runBlocking {
        assertEquals("3", get(d(), "k=3&other=9"))
        assertEquals("3", BufferedHttpDispatcher.queryParamFirst("k=3&other=9", "k"))
    }
    @Test fun missingIsNull() = runBlocking {
        assertEquals("null", get(d(), "a=1&b=2"))
        assertNull(BufferedHttpDispatcher.queryParamFirst("a=1", "k"))
    }
    @Test fun firstOfRepeated() = runBlocking {
        assertEquals("1", get(d(), "k=1&k=2&k=3"))
    }
    @Test fun allOfRepeated() = runBlocking {
        assertEquals("1,2,3", get(d(readAll = true), "k=1&k=2&k=3"))
    }
    @Test fun emptyValue() = runBlocking {
        assertEquals("", BufferedHttpDispatcher.queryParamFirst("k=&x=1", "k"))
        assertEquals("", BufferedHttpDispatcher.queryParamFirst("x=1&k", "k"))
    }
    @Test fun percentAndPlusEncoding() = runBlocking {
        assertEquals("a b", BufferedHttpDispatcher.queryParamFirst("k=a+b", "k"))
        assertEquals("/", BufferedHttpDispatcher.queryParamFirst("k=%2F", "k"))
        assertEquals("v", BufferedHttpDispatcher.queryParamFirst("%6b=v", "k")) // encoded key 'k'
    }
    @Test fun prefixKeyDoesNotMatch() = runBlocking {
        assertEquals("real", BufferedHttpDispatcher.queryParamFirst("kk=no&k=real", "k"))
        assertNull(BufferedHttpDispatcher.queryParamFirst("kk=no", "k"))
    }
    @Test fun agreesWithMapParseAcrossShapes() {
        for (q in listOf("k=3&b=4", "k=1&k=2", "kk=x&k=y", "k=%2F&z=1", "k=a+b", "a=1", "k=")) {
            assertEquals(refFirst(q, "k"), BufferedHttpDispatcher.queryParamFirst(q, "k"), "mismatch on <<$q>>")
        }
    }
}
