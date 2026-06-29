package neton.http

import neton.core.component.HttpEngine
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter

/** Creates an optional HTTP adapter that has been linked by the application. */
interface HttpAdapterProvider {
    val engine: HttpEngine

    fun create(
        serverConfig: HttpServerConfig,
        converterRegistry: ParamConverterRegistry,
    ): HttpAdapter
}
