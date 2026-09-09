import neton.core.Neton
import neton.http.http
import neton.routing.*

/**
 * Neton TechEmpower-style benchmark application (Hyper4k default engine).
 *
 * The bare http { } below resolves to Hyper4k. To benchmark Ktor instead, the only
 * change is passing the adapter explicitly: http(::KtorHttpAdapter).
 */
fun main(args: Array<String>) {
    Neton.run(args) {

        http {
            port = 8090
        }

        routing {
            get("/plaintext") {
                "Hello, World!"
            }
            get("/json") {
                mapOf("message" to "Hello, World!")
            }
            // Large JSON body via response.write (application/json) — the same
            // committed path the arena's /json/{count} uses, for validating
            // response compression on that branch.
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
