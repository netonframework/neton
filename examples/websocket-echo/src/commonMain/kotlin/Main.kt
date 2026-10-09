@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package example.websocket

import kotlinx.coroutines.flow.collect
import neton.core.Neton
import neton.core.annotations.Controller
import neton.http.http
import neton.routing.routing
import neton.ws.*

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        websocket { pingIntervalMillis = 30000; pongTimeoutMillis = 10000 }
        routing {
            webSocket("/echo") { session -> session.incoming.collect { session.send(it) } }
        }
        modules(neton.core.generated.GeneratedInitializer)
    }
}

@Controller("/rooms")
class RoomSocket {
    @WebSocket("/{room}")
    suspend fun room(session: WebSocketSession, room: Long) {
        session.incoming.collect { message ->
            when (message) {
                is WebSocketMessage.Text -> session.send("$room:${message.value}")
                is WebSocketMessage.Binary -> session.send(message)
            }
        }
    }
}
