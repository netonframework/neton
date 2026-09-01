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
        }
    }
}
