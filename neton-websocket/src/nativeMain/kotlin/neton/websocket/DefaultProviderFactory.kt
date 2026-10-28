@file:OptIn(neton.websocket.spi.ExperimentalWebSocketEngineApi::class)

package neton.websocket

import neton.websocket.engine.default.DefaultWebSocketEngineProvider
import neton.websocket.spi.WebSocketEngineProvider

internal actual fun createDefaultWebSocketEngineProvider(): WebSocketEngineProvider =
    DefaultWebSocketEngineProvider()
