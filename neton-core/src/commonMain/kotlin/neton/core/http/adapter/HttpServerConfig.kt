package neton.core.http.adapter

/**
 * Transport-level settings every engine adapter is constructed with.
 *
 * Transport only: policy that the shared dispatcher applies (CORS, security, access
 * logging) is resolved from [neton.core.component.NetonContext], not carried here.
 */
data class HttpServerConfig(
    val port: Int,
    val timeout: Long = 30000L,
    val maxConnections: Int = 1000,
    val enableCompression: Boolean = true,
)

/**
 * How an application installs an engine.
 *
 * A constructor reference (`::Hyper4kHttpAdapter`) rather than a name, so the engine
 * is a compile-time dependency of the application and never a string looked up at
 * runtime. It lives here, with [HttpAdapter], because it is part of the engine
 * contract: neton-http consumes it but does not own it.
 *
 * Takes transport config only. Anything else an engine needs is read from
 * [neton.core.component.NetonContext], so adding a capability does not change this
 * signature and break every adapter.
 */
typealias HttpAdapterFactory = (HttpServerConfig) -> HttpAdapter
