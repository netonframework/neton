import kotlinx.coroutines.delay
import neton.core.Neton
import neton.core.http.sse
import neton.core.component.tls
import neton.http.http
import neton.http.netonstream.NetonStreamHttpAdapter
import neton.routing.*

/**
 * The netonstream engine end to end: HTTP/1.1 and h2c on one port, gzip, SSE and cookies.
 * The engine is selected explicitly; `http { }` without an adapter would be hyper4k's.
 */
fun main(args: Array<String>) {
    Neton.run(args) {
        http(::NetonStreamHttpAdapter) {
            port = 8080
            // `--tls`: terminate TLS with certs/cert.pem + certs/key.pem, HTTP/2 or HTTP/1.1 by ALPN.
            // (The DSL, not `[http.tls]` in application.conf: the framework's config parser does
            // not read arrays yet, so `alpnProtocols` from the file falls back to http/1.1.)
            if ("--tls" in args) {
                tls {
                    certificatePath = "certs/cert.pem"
                    privateKeyPath = "certs/key.pem"
                    alpnProtocols = listOf("h2", "http/1.1")
                }
            }
        }

        routing {
            get("/") {
                "Hello from the netonstream engine"
            }

            get("/items") {
                (1..50).map { mapOf("id" to it, "name" to "item-$it") }
            }

            get("/login") { ctx ->
                ctx.response.cookie("session", "abc123", path = "/", httpOnly = true)
                ctx.response.cookie("theme", "dark", maxAge = 3600)
                ctx.response.text("logged in")
                null
            }

            get("/stream") { ctx ->
                val count = ctx.request.queryParams["count"]?.toIntOrNull() ?: 5
                ctx.response.sse {
                    repeat(count) { i ->
                        event(data = """{"seq":$i}""")
                        delay(100)
                    }
                    event(data = "[DONE]")
                }
                null
            }
        }
    }
}
