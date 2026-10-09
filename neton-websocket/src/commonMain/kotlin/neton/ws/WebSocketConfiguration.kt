package neton.ws

import neton.core.component.NetonContext
import neton.core.config.ConfigLoader

/** Same file and precedence as HTTP: application configuration overrides the DSL. */
internal fun WebSocketConfig.loadApplicationConfig(ctx: NetonContext) {
    val application = ConfigLoader.loadApplicationConfig("config", ConfigLoader.resolveEnvironment(ctx.args), ctx.args)
    val section = ConfigLoader.getConfigValue(application, "websocket") as? Map<*, *> ?: return
    fun long(name: String): Long? = section[name]?.let {
        it.toString().toLongOrNull() ?: error("websocket.$name must be an integer")
    }
    fun int(name: String): Int? = long(name)?.also { require(it in 0..Int.MAX_VALUE.toLong()) { "websocket.$name out of range" } }?.toInt()
    fun bool(name: String): Boolean? = section[name]?.let {
        it.toString().toBooleanStrictOrNull() ?: error("websocket.$name must be true or false")
    }
    engineLimits = engineLimits.copy(
        maxMessageBytes = int("maxMessageBytes") ?: engineLimits.maxMessageBytes,
        maxFrameBytes = int("maxFrameBytes") ?: engineLimits.maxFrameBytes,
        readBufferBytes = int("readBufferBytes") ?: engineLimits.readBufferBytes,
        writeBufferBytes = int("writeBufferBytes") ?: engineLimits.writeBufferBytes,
        maxWriteBufferBytes = int("maxWriteBufferBytes") ?: engineLimits.maxWriteBufferBytes,
    )
    int("maxConnections")?.let { maxConnections = it }
    int("queueCapacity")?.let { queueCapacity = it }
    int("maxSuspendedSends")?.let { maxSuspendedSends = it }
    int("maxSuspendedSendsTotal")?.let { maxSuspendedSendsTotal = it }
    long("maxBufferedBytes")?.let { maxBufferedBytes = it }
    long("maxQueuedBytesPerConnection")?.let { maxQueuedBytesPerConnection = it }
    long("maxSuspendedSendBytes")?.let { maxSuspendedSendBytes = it }
    long("pingIntervalMillis")?.let { pingIntervalMillis = it }
    long("pongTimeoutMillis")?.let { pongTimeoutMillis = it }
    long("idleTimeoutMillis")?.let { idleTimeoutMillis = it }
    long("consumerTimeoutMillis")?.let { consumerTimeoutMillis = it }
    long("writeTimeoutMillis")?.let { writeTimeoutMillis = it }
    long("closeTimeoutMillis")?.let { closeTimeoutMillis = it }
    long("handlerShutdownMillis")?.let { handlerShutdownMillis = it }
    bool("allowMissingOrigin")?.let { allowMissingOrigin = it }
    require(bool("compression") != true) { "Managed WebSocket compression is not supported yet" }
    section["allowedOrigins"]?.let { origins ->
        require(origins is List<*> && origins.all { it is String }) { "websocket.allowedOrigins must be a list of strings" }
        allowedOrigins = origins.filterIsInstance<String>().toSet()
    }
}
