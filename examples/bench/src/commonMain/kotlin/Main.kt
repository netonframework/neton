import neton.core.Neton
import neton.http.http
import neton.routing.*

/**
 * Neton TechEmpower-style benchmark application (Ktor CIO default engine).
 *
 * The exact same routes are used for the Hyper4k and May4k bench apps; the only
 * difference between the three apps is the adapter passed to http(...).
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
