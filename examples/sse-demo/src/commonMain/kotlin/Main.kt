import kotlinx.coroutines.delay
import neton.core.Neton
import neton.core.http.sse
import neton.http.http
import neton.routing.*

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        routing {
            get("/stream") { ctx ->
                val count = ctx.request.queryParams["count"]?.toIntOrNull() ?: 5
                val delayMs = ctx.request.queryParams["delayMs"]?.toLongOrNull() ?: 200L
                ctx.response.sse {
                    repeat(count) { i ->
                        event(data = """{"seq":$i,"ts":${kotlin.time.Clock.System.now().toEpochMilliseconds()}}""")
                        delay(delayMs)
                    }
                    event(data = "[DONE]")
                }
                null
            }
        }
    }
}
