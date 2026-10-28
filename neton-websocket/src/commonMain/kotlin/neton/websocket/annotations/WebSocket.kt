package neton.websocket.annotations

import neton.websocket.InboundPolicy

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class WebSocket(val value: String = "", val subprotocols: Array<String> = [], val inboundPolicy: InboundPolicy = InboundPolicy.BACKPRESSURE)
