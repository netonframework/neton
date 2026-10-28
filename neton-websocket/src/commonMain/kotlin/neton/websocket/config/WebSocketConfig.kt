@file:OptIn(neton.websocket.spi.ExperimentalWebSocketEngineApi::class)

package neton.websocket.config

import neton.websocket.normalizeOrigin
import neton.websocket.spi.EngineLimits
import neton.websocket.spi.WebSocketEngineCapability

class WebSocketConfig {
    var engineLimits: EngineLimits = EngineLimits()
    /** Must be explicit: an unsupported contract cannot silently degrade. */
    var requiredCapabilities: Set<WebSocketEngineCapability> = setOf(WebSocketEngineCapability.MESSAGE_LIMITS, WebSocketEngineCapability.PREALLOCATION_BUDGET)
    var maxConnections: Int = 16384
    var maxBufferedBytes: Long = 512L * 1024 * 1024
    var maxQueuedBytesPerConnection: Long = 8L * 1024 * 1024
    var queueCapacity: Int = 16
    var maxSuspendedSends: Int = 16
    var maxSuspendedSendsTotal: Int = 4096
    var maxSuspendedSendBytes: Long = 64L * 1024 * 1024
    var pingIntervalMillis: Long = 30000
    var pongTimeoutMillis: Long = 10000
    var idleTimeoutMillis: Long = 0
    var consumerTimeoutMillis: Long = 30000
    var writeTimeoutMillis: Long = 10000
    var closeTimeoutMillis: Long = 5000
    var handlerShutdownMillis: Long = 1000
    /** Empty means same-origin. Absent Origin is allowed for non-browser clients. */
    var allowedOrigins: Set<String> = emptySet()
    var allowMissingOrigin: Boolean = true
    var handshakeHeaderNames: Set<String> = emptySet()
    var maxHandshakeSnapshotBytes: Long = 65536

    internal fun snapshot() = WebSocketConfig().also {
        it.engineLimits = engineLimits; it.requiredCapabilities = requiredCapabilities.toSet()
        it.maxConnections = maxConnections; it.maxBufferedBytes = maxBufferedBytes
        it.maxQueuedBytesPerConnection = maxQueuedBytesPerConnection; it.queueCapacity = queueCapacity
        it.maxSuspendedSends = maxSuspendedSends; it.maxSuspendedSendsTotal = maxSuspendedSendsTotal
        it.maxSuspendedSendBytes = maxSuspendedSendBytes
        it.pingIntervalMillis = pingIntervalMillis; it.pongTimeoutMillis = pongTimeoutMillis
        it.idleTimeoutMillis = idleTimeoutMillis; it.consumerTimeoutMillis = consumerTimeoutMillis
        it.writeTimeoutMillis = writeTimeoutMillis; it.closeTimeoutMillis = closeTimeoutMillis
        it.handlerShutdownMillis = handlerShutdownMillis
        it.allowedOrigins = allowedOrigins.toSet(); it.allowMissingOrigin = allowMissingOrigin
        it.handshakeHeaderNames = handshakeHeaderNames.toSet(); it.maxHandshakeSnapshotBytes = maxHandshakeSnapshotBytes
    }

    internal fun validate() {
        require(maxConnections > 0 && queueCapacity > 0)
        require(maxSuspendedSends > 0 && maxSuspendedSendsTotal > 0 && maxSuspendedSendBytes > 0)
        require(maxQueuedBytesPerConnection >= engineLimits.maxMessageBytes.toLong()) {
            "maxQueuedBytesPerConnection must fit maxMessageBytes"
        }
        require(maxBufferedBytes >= maxQueuedBytesPerConnection)
        require(maxSuspendedSendBytes >= engineLimits.maxMessageBytes.toLong())
        require(maxHandshakeSnapshotBytes > 0)
        require(handshakeHeaderNames.all { name -> name.isNotEmpty() && name.all { it.code in 33..126 && it !in "()<>@,;:\\\"/[]?={} " } })
        require(pingIntervalMillis >= 0 && pongTimeoutMillis >= 0 && idleTimeoutMillis >= 0)
        require(consumerTimeoutMillis > 0 && writeTimeoutMillis > 0 && closeTimeoutMillis > 0 && handlerShutdownMillis > 0)
        require(listOf(pingIntervalMillis, pongTimeoutMillis, idleTimeoutMillis, consumerTimeoutMillis,
            writeTimeoutMillis, closeTimeoutMillis, handlerShutdownMillis).all { it <= Int.MAX_VALUE.toLong() }) {
            "WebSocket timeouts must not exceed Int.MAX_VALUE milliseconds"
        }
        require(allowedOrigins.none { it == "*" || normalizeOrigin(it) == null }) { "Use explicit http/https origins" }
    }
}
