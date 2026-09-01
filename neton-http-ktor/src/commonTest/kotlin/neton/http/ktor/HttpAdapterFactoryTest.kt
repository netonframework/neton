package neton.http.ktor

import neton.core.http.adapter.HttpAdapterFactory

import neton.core.http.adapter.HttpServerConfig

import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpAdapterFactoryTest {
    private val config = HttpServerConfig(port = 8080)

    @Test
    fun ktorConstructorMatchesAdapterFactory() {
        val factory: HttpAdapterFactory = ::KtorHttpAdapter

        assertIs<KtorHttpAdapter>(factory(config))
    }

    @Test
    fun customConstructorCanBeInjected() {
        val factory: HttpAdapterFactory = { serverConfig -> StubAdapter(serverConfig.port) }

        assertEquals(8080, factory(config).port())
    }
}

private class StubAdapter(private val configuredPort: Int) : HttpAdapter {
    override val capabilities = emptySet<neton.core.http.adapter.HttpCapability>()
    override suspend fun start(ctx: NetonContext, onStarted: (suspend (coldStartMs: Long) -> Unit)?) = Unit
    override suspend fun stop() = Unit
    override fun port(): Int = configuredPort
    override fun adapterName(): String = "stub"
}
