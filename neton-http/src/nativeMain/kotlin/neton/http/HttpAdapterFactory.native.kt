package neton.http

import neton.core.component.HttpEngine
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter

internal actual fun createHttpAdapter(
    engine: HttpEngine,
    serverConfig: HttpServerConfig,
    converterRegistry: ParamConverterRegistry,
): HttpAdapter = when (engine) {
    HttpEngine.KTOR -> KtorHttpAdapter(serverConfig, converterRegistry)
    HttpEngine.HYPER4K -> HyperHttpAdapter(serverConfig)
}
