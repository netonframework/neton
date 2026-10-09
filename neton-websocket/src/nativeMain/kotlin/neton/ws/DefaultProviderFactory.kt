@file:OptIn(neton.ws.spi.ExperimentalWebSocketEngineApi::class)

package neton.ws

import neton.ws.engine.default.DefaultWebSocketEngineProvider
import neton.ws.spi.WebSocketEngineProvider

internal actual fun createDefaultWebSocketEngineProvider(): WebSocketEngineProvider =
    DefaultWebSocketEngineProvider()
