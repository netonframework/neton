import neton.core.Neton
import neton.http.http
import neton.routing.*
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * Neton TechEmpower-style benchmark application (Hyper4k default engine).
 *
 * GC is tunable via CLI args for A/B without rebuilds (stripped before Neton.run):
 *   gcmin=<MB> gctarget=<MB> gcauto=0
 */
@OptIn(NativeRuntimeApi::class)
private fun tuneGc(args: Array<String>) {
    fun mb(name: String): Long? =
        args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.toLongOrNull()
    mb("gcmin")?.let { GC.minHeapBytes = it * 1024 * 1024 }
    mb("gctarget")?.let { GC.targetHeapBytes = it * 1024 * 1024 }
    if (args.any { it == "gcauto=0" }) GC.autotune = false
}

fun main(args: Array<String>) {
    tuneGc(args)
    val passthrough = args.filterNot {
        it.startsWith("gcmin=") || it.startsWith("gctarget=") || it == "gcauto=0"
    }.toTypedArray()
    Neton.run(passthrough) {

        http {
            port = 8090
        }

        routing {
            get("/plaintext") {
                "Hello, World!"
            }
            get("/sum") {
                val a = it.request.queryParam("a")?.toIntOrNull() ?: 0
                val b = it.request.queryParam("b")?.toIntOrNull() ?: 0
                it.response.text((a + b).toString())
            }
            get("/json") {
                mapOf("message" to "Hello, World!")
            }
            // End-to-end verification that a handler still reads request headers
            // correctly under lazy/borrowed materialization.
            get("/echo-header") {
                it.response.text(it.request.header("X-Echo") ?: "<none>")
            }
            get("/jsonbig") {
                val sb = StringBuilder("[")
                for (i in 1..25) {
                    if (i > 1) sb.append(',')
                    sb.append("""{"id":$i,"name":"item-name-number-$i","category":"category-of-goods-$i",""")
                    sb.append(""""description":"a reasonably long description string for row $i","active":${i % 2 == 0}}""")
                }
                sb.append(']')
                it.response.contentType = "application/json; charset=utf-8"
                it.response.write(sb.toString().encodeToByteArray())
            }
        }
    }
}
