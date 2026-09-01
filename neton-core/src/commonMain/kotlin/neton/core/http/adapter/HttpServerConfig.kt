package neton.core.http.adapter

import neton.core.component.CorsConfig
import neton.core.http.ParamConverterRegistry

/** Transport-level settings every engine adapter is constructed with. */
data class HttpServerConfig(
    val port: Int,
    val timeout: Long = 30000L,
    val maxConnections: Int = 1000,
    val enableCompression: Boolean = true,
    val corsConfig: CorsConfig? = null
)

/**
 * How an application installs an engine.
 *
 * A constructor reference (`::Hyper4kHttpAdapter`) rather than a name, so the engine
 * is a compile-time dependency of the application and never a string looked up at
 * runtime. It lives here, with [HttpAdapter], because it is part of the engine
 * contract: neton-http consumes it but does not own it.
 */
typealias HttpAdapterFactory = (HttpServerConfig, ParamConverterRegistry) -> HttpAdapter
