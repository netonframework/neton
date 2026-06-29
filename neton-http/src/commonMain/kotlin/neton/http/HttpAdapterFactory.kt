package neton.http

import neton.core.component.HttpEngine
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter

internal expect fun createHttpAdapter(
    engine: HttpEngine,
    serverConfig: HttpServerConfig,
    converterRegistry: ParamConverterRegistry,
): HttpAdapter
