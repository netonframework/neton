package neton.http

import kotlinx.coroutines.delay
import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.interfaces.RequestEngine
import neton.core.mock.MockRequestEngine
import neton.core.module.ModuleInitializer
import kotlin.test.Test
import kotlin.test.assertTrue

class KtorLifecycleSmokeTest {

    @Test
    fun cioListenerReachesReadyAndStopsCleanly() {
        var ready = false

        Neton.run(emptyArray()) {
            install(RequestEngineComponent) {}
            http { port = 0 }
            modules(NoopModule)
            onReady {
                ready = true
                delay(250)
                get<HttpAdapter>().stop()
            }
        }

        assertTrue(ready)
    }

    @Test
    fun cioListenerCanRestartAfterCleanShutdown() {
        repeat(3) {
            var ready = false
            Neton.run(emptyArray()) {
                install(RequestEngineComponent) {}
                http { port = 0 }
                modules(NoopModule)
                onReady {
                    ready = true
                    get<HttpAdapter>().stop()
                }
            }
            assertTrue(ready)
        }
    }

    private object RequestEngineComponent : NetonComponent<Unit> {
        override fun defaultConfig() = Unit

        override suspend fun init(ctx: NetonContext, config: Unit) {
            ctx.bind(RequestEngine::class, MockRequestEngine())
        }
    }

    private object NoopModule : ModuleInitializer {
        override val moduleId: String = "ktor-lifecycle-smoke"
        override fun initialize(ctx: NetonContext) = Unit
    }
}
