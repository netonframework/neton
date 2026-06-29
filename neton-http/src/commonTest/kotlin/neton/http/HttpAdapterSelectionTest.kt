package neton.http

import neton.core.component.HttpEngine
import neton.core.component.NetonContext
import neton.core.http.DefaultParamConverterRegistry
import neton.core.http.ParamConverterRegistry
import neton.core.http.adapter.HttpAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HttpAdapterSelectionTest {
    private val serverConfig = HttpServerConfig(port = 8080)
    private val converters = DefaultParamConverterRegistry()

    @Test
    fun ktorIsAvailableWithoutOptionalProvider() {
        assertIs<KtorHttpAdapter>(
            selectHttpAdapter(HttpEngine.KTOR, serverConfig, converters, provider = null)
        )
    }

    @Test
    fun optionalEngineFailsWhenProviderIsMissing() {
        val error = assertFailsWith<IllegalStateException> {
            selectHttpAdapter(HttpEngine.HYPER4K, serverConfig, converters, provider = null)
        }

        assertEquals(true, error.message?.contains("not installed"))
    }

    @Test
    fun optionalEngineUsesRegisteredProvider() {
        val expected = StubAdapter
        val provider = object : HttpAdapterProvider {
            override val engine: HttpEngine = HttpEngine.HYPER4K

            override fun create(
                serverConfig: HttpServerConfig,
                converterRegistry: ParamConverterRegistry,
            ): HttpAdapter = expected
        }

        assertEquals(expected, selectHttpAdapter(HttpEngine.HYPER4K, serverConfig, converters, provider))
    }
}

private object StubAdapter : HttpAdapter {
    override suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)?) = Unit
    override suspend fun stop() = Unit
    override fun port(): Int = 0
    override fun adapterName(): String = "stub"
}
