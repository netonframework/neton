package neton.http.static

import neton.core.component.NetonContext
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.http.adapter.HttpServerConfig
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.convert
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real files on disk, exercised through the full dispatcher, on both the happy
 * path and the ones the spec calls out: replace, delete, traversal, HEAD,
 * conditional, Range, and pre-compressed selection.
 */
@OptIn(ExperimentalForeignApi::class)
class StaticFilesTest {

    private lateinit var dir: String

    @BeforeTest
    fun setUp() {
        dir = "/tmp/neton-static-test-" + getpid() + "-" + (nextId++)
        mkdir(dir, "0755".toUInt(8).convert())
    }

    @AfterTest
    fun tearDown() {
        // Best-effort recursive delete via shell; test dirs are disposable.
        system("rm -rf " + dir)
    }

    private var nextId = 0L

    private fun writeFile(relative: String, content: ByteArray) {
        val full = dir + "/" + relative
        val slash = full.lastIndexOf('/')
        if (slash > 0) system("mkdir -p " + full.substring(0, slash))
        val fd = open(full, O_WRONLY or O_CREAT or O_TRUNC, "0644".toUInt(8))
        check(fd >= 0) { "cannot create " + full }
        if (content.isNotEmpty()) {
            content.usePinned { write(fd, it.addressOf(0), content.size.convert()) }
        }
        close(fd)
    }

    private fun dispatcher(configure: (StaticFilesConfig.() -> Unit)? = null): BufferedHttpDispatcher {
        val engine = object : RequestEngine {
            private val routes = mutableListOf<RouteDefinition>()
            override fun registerRoute(route: RouteDefinition) { routes.add(route) }
            override fun getRoutes() = routes.toList()
        }
        engine.staticFiles("/assets", dir, configure)
        val ctx = NetonContext(emptyArray()).apply { bind(RequestEngine::class, engine) }
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    private fun get(
        path: String,
        method: String = "GET",
        headers: Map<String, List<String>> = emptyMap(),
    ) = BufferedHttpRequest(method, path, "", headers, ByteArray(0))

    @Test
    fun servesAFileWithItsContentTypeAndEtag() = runBlocking {
        writeFile("app.css", "body{}".encodeToByteArray())
        val d = dispatcher()
        val r = d.dispatch(get("/assets/app.css"))
        assertEquals(200, r.status)
        assertEquals("body{}", r.body.decodeToString())
        assertTrue(r.headers["Content-Type"]?.first()?.startsWith("text/css") == true)
        assertTrue(r.headers.containsKey("ETag"), "must carry an ETag")
    }

    @Test
    fun missingFileIs404() = runBlocking {
        val d = dispatcher()
        assertEquals(404, d.dispatch(get("/assets/nope.js")).status)
    }

    @Test
    fun followsTheDiskWhenAFileIsReplaced() = runBlocking {
        writeFile("data.txt", "one".encodeToByteArray())
        val d = dispatcher()
        assertEquals("one", d.dispatch(get("/assets/data.txt")).body.decodeToString())
        // Rewrite with different length; mtime advances (atomic-ish overwrite).
        system("sleep 0.01")
        writeFile("data.txt", "two-longer".encodeToByteArray())
        assertEquals("two-longer", d.dispatch(get("/assets/data.txt")).body.decodeToString())
    }

    @Test
    fun stopsServingAfterDelete() = runBlocking {
        writeFile("gone.txt", "here".encodeToByteArray())
        val d = dispatcher()
        assertEquals(200, d.dispatch(get("/assets/gone.txt")).status)
        system("rm -f " + dir + "/gone.txt")
        assertEquals(404, d.dispatch(get("/assets/gone.txt")).status)
    }

    @Test
    fun refusesPathTraversal() = runBlocking {
        writeFile("ok.txt", "ok".encodeToByteArray())
        // A secret one level above the mount root.
        system("echo secret > " + dir + "/../neton-static-secret-" + getpid())
        val d = dispatcher()
        // Encoded and raw dot-dot must both be refused.
        assertEquals(404, d.dispatch(get("/assets/../neton-static-secret-" + getpid())).status)
        assertEquals(404, d.dispatch(get("/assets/%2e%2e/neton-static-secret-" + getpid())).status)
        system("rm -f " + dir + "/../neton-static-secret-" + getpid())
        Unit
    }

    @Test
    fun refusesDotFiles() = runBlocking {
        writeFile(".env", "SECRET=1".encodeToByteArray())
        val d = dispatcher()
        assertEquals(404, d.dispatch(get("/assets/.env")).status)
    }

    @Test
    fun headReturnsNoBodyButContentLength() = runBlocking {
        writeFile("page.html", "<h1>hi</h1>".encodeToByteArray())
        val d = dispatcher()
        val r = d.dispatch(get("/assets/page.html", method = "HEAD"))
        assertEquals(200, r.status)
        assertEquals(0, r.body.size)
        assertEquals("11", r.headers["Content-Length"]?.first())
    }

    @Test
    fun ifNoneMatchGets304() = runBlocking {
        writeFile("x.txt", "abc".encodeToByteArray())
        val d = dispatcher()
        val first = d.dispatch(get("/assets/x.txt"))
        val etag = first.headers["ETag"]!!.first()
        val second = d.dispatch(get("/assets/x.txt", headers = mapOf("If-None-Match" to listOf(etag))))
        assertEquals(304, second.status)
        assertEquals(0, second.body.size)
    }

    @Test
    fun rangeReturns206WithTheRequestedSlice() = runBlocking {
        writeFile("big.txt", "0123456789".encodeToByteArray())
        val d = dispatcher()
        val r = d.dispatch(get("/assets/big.txt", headers = mapOf("Range" to listOf("bytes=2-5"))))
        assertEquals(206, r.status)
        assertEquals("2345", r.body.decodeToString())
        assertEquals("bytes 2-5/10", r.headers["Content-Range"]?.first())
    }

    @Test
    fun anUnsatisfiableRangeGets416() = runBlocking {
        writeFile("small.txt", "abc".encodeToByteArray())
        val d = dispatcher()
        val r = d.dispatch(get("/assets/small.txt", headers = mapOf("Range" to listOf("bytes=99-200"))))
        assertEquals(416, r.status)
        assertEquals("bytes */3", r.headers["Content-Range"]?.first())
    }

    @Test
    fun servesPrecompressedVariantWhenOffered() = runBlocking {
        writeFile("bundle.js", "SOURCE".encodeToByteArray())
        writeFile("bundle.js.br", "BROTLI".encodeToByteArray())
        val d = dispatcher { precompressed = true }
        val r = d.dispatch(get("/assets/bundle.js", headers = mapOf("Accept-Encoding" to listOf("br, gzip"))))
        assertEquals(200, r.status)
        assertEquals("BROTLI", r.body.decodeToString())
        assertEquals("br", r.headers["Content-Encoding"]?.first())
        assertTrue(r.headers["Vary"]?.first()?.contains("Accept-Encoding") == true)
        // Its Content-Type stays the original file's, not the variant's.
        assertTrue(r.headers["Content-Type"]?.first()?.contains("javascript") == true)
    }

    @Test
    fun withoutAcceptEncodingServesTheOriginal() = runBlocking {
        writeFile("bundle.js", "SOURCE".encodeToByteArray())
        writeFile("bundle.js.br", "BROTLI".encodeToByteArray())
        val d = dispatcher { precompressed = true }
        val r = d.dispatch(get("/assets/bundle.js"))
        assertEquals("SOURCE", r.body.decodeToString())
        assertNull(r.headers["Content-Encoding"])
    }

    @Test
    fun servesIndexForTheMountRoot() = runBlocking {
        writeFile("index.html", "HOME".encodeToByteArray())
        val d = dispatcher()
        assertEquals("HOME", d.dispatch(get("/assets")).body.decodeToString())
        assertEquals("HOME", d.dispatch(get("/assets/")).body.decodeToString())
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class StaticFilesContractFixTest {
    private var nextId = 0L
    private lateinit var dir: String

    @kotlin.test.BeforeTest fun setUp() { dir = "/tmp/neton-static-fix-" + platform.posix.getpid() + "-" + (nextId++); platform.posix.mkdir(dir, "0755".toUInt(8).convert()) }
    @kotlin.test.AfterTest fun tearDown() { platform.posix.system("rm -rf " + dir) }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun writeFile(rel: String, content: ByteArray) {
        val full = dir + "/" + rel
        val fd = platform.posix.open(full, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, "0644".toUInt(8))
        if (content.isNotEmpty()) content.usePinned { platform.posix.write(fd, it.addressOf(0), content.size.convert()) }
        platform.posix.close(fd)
    }

    private fun dispatcher(): BufferedHttpDispatcher {
        val engine = object : neton.core.interfaces.RequestEngine {
            private val r = mutableListOf<neton.core.interfaces.RouteDefinition>()
            override fun registerRoute(route: neton.core.interfaces.RouteDefinition) { r.add(route) }
            override fun getRoutes() = r.toList()
        }
        engine.staticFiles("/s", dir) { precompressed = true }
        val ctx = neton.core.component.NetonContext(emptyArray()).apply { bind(neton.core.interfaces.RequestEngine::class, engine) }
        return BufferedHttpDispatcher(neton.core.http.adapter.HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    private fun req(path: String, method: String = "GET", headers: Map<String, List<String>> = emptyMap()) =
        BufferedHttpRequest(method, path, "", headers, ByteArray(0))

    @kotlin.test.Test
    fun gzipWithQZeroIsNotServedGzipped() = kotlinx.coroutines.runBlocking {
        writeFile("a.js", "SOURCE".encodeToByteArray()); writeFile("a.js.gz", "GZ".encodeToByteArray())
        val d = dispatcher()
        val r = d.dispatch(req("/s/a.js", headers = mapOf("Accept-Encoding" to listOf("gzip;q=0"))))
        kotlin.test.assertEquals("SOURCE", r.body.decodeToString(), "q=0 refuses gzip")
        kotlin.test.assertNull(r.headers["Content-Encoding"])
    }

    @kotlin.test.Test
    fun headSelectsTheSameVariantAsGet() = kotlinx.coroutines.runBlocking {
        writeFile("b.js", "SOURCE".encodeToByteArray()); writeFile("b.js.gz", "GZ".encodeToByteArray())
        val d = dispatcher()
        val ae = mapOf("Accept-Encoding" to listOf("gzip"))
        val get = d.dispatch(req("/s/b.js", headers = ae))
        val head = d.dispatch(req("/s/b.js", method = "HEAD", headers = ae))
        kotlin.test.assertEquals("gzip", get.headers["Content-Encoding"]?.first())
        kotlin.test.assertEquals(get.headers["Content-Encoding"], head.headers["Content-Encoding"], "HEAD encoding must match GET")
        kotlin.test.assertEquals(get.headers["ETag"], head.headers["ETag"], "HEAD ETag must match GET")
        kotlin.test.assertEquals(0, head.body.size)
    }

    @kotlin.test.Test
    fun anAtomicReplaceIsSeenOnTheNextRequest() = kotlinx.coroutines.runBlocking {
        writeFile("c.txt", "old".encodeToByteArray())
        val d = dispatcher()
        kotlin.test.assertEquals("old", d.dispatch(req("/s/c.txt")).body.decodeToString())
        // Atomic replace: write temp then rename (mtime always moves).
        writeFile("c.txt.tmp", "brand-new-content".encodeToByteArray())
        platform.posix.rename(dir + "/c.txt.tmp", dir + "/c.txt")
        kotlin.test.assertEquals("brand-new-content", d.dispatch(req("/s/c.txt")).body.decodeToString())
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class StaticLargeFileTest {
    private var nextId = 0L
    private lateinit var dir: String
    @kotlin.test.BeforeTest fun setUp() { dir = "/tmp/neton-static-big-" + platform.posix.getpid() + "-" + (nextId++); platform.posix.mkdir(dir, "0755".toUInt(8).convert()) }
    @kotlin.test.AfterTest fun tearDown() { platform.posix.system("rm -rf " + dir) }

    private fun writeFile(rel: String, content: ByteArray) {
        val fd = platform.posix.open(dir + "/" + rel, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, "0644".toUInt(8))
        if (content.isNotEmpty()) content.usePinned { platform.posix.write(fd, it.addressOf(0), content.size.convert()) }
        platform.posix.close(fd)
    }
    private fun dispatcher(): BufferedHttpDispatcher {
        val engine = object : neton.core.interfaces.RequestEngine {
            private val r = mutableListOf<neton.core.interfaces.RouteDefinition>()
            override fun registerRoute(route: neton.core.interfaces.RouteDefinition) { r.add(route) }
            override fun getRoutes() = r.toList()
        }
        engine.staticFiles("/s", dir)
        val ctx = neton.core.component.NetonContext(emptyArray()).apply { bind(neton.core.interfaces.RequestEngine::class, engine) }
        return BufferedHttpDispatcher(neton.core.http.adapter.HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    @kotlin.test.Test
    fun aFileOverTheStreamThresholdArrivesCompleteAndInOrder() = kotlinx.coroutines.runBlocking {
        // 700 KB > 256 KB threshold: served through the chunked stream path.
        val n = 700 * 1024
        val content = ByteArray(n) { (it % 251).toByte() }
        writeFile("big.bin", content)
        val d = dispatcher()
        val r = d.dispatch(BufferedHttpRequest("GET", "/s/big.bin", "", emptyMap(), ByteArray(0)))
        kotlin.test.assertEquals(200, r.status)
        kotlin.test.assertEquals(n, r.body.size, "every byte of the streamed file must arrive")
        kotlin.test.assertTrue(r.body.contentEquals(content), "streamed chunks must reassemble in order")
    }
}
