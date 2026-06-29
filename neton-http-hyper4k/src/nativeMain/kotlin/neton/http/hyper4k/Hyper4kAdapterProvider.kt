package neton.http.hyper4k

import neton.core.Neton
import neton.core.component.HttpEngine
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter
import neton.http.HttpAdapterProvider
import neton.http.HttpServerConfig

object Hyper4kAdapterProvider : HttpAdapterProvider {
    override val engine: HttpEngine = HttpEngine.HYPER4K

    override fun create(
        serverConfig: HttpServerConfig,
        converterRegistry: ParamConverterRegistry,
    ): HttpAdapter = HyperHttpAdapter(serverConfig)
}

/** Makes the optional Hyper adapter available to the `http.engine` configuration. */
fun Neton.LaunchBuilder.enableHyper4kAdapter() {
    bind(HttpAdapterProvider::class, Hyper4kAdapterProvider)
}
