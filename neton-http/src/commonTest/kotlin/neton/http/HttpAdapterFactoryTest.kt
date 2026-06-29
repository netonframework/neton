package neton.http

import neton.core.component.NetonContext
import neton.core.http.DefaultParamConverterRegistry
import neton.core.http.adapter.HttpAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpAdapterFactoryTest {
    private val config = HttpServerConfig(port = 8080)
    private val converters = DefaultParamConverterRegistry()

    @Test
    fun ktorConstructorMatchesAdapterFactory() {
        val factory: HttpAdapterFactory = ::KtorHttpAdapter

        assertIs<KtorHttpAdapter>(factory(config, converters))
    }

    @Test
    fun customConstructorCanBeInjected() {
        val factory: HttpAdapterFactory = { serverConfig, _ -> StubAdapter(serverConfig.port) }

        assertEquals(8080, factory(config, converters).port())
    }
}

private class StubAdapter(private val configuredPort: Int) : HttpAdapter {
    override suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)?) = Unit
    override suspend fun stop() = Unit
    override fun port(): Int = configuredPort
    override fun adapterName(): String = "stub"
}
