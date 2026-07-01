@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.interfaces.RequestEngine
import neton.core.mock.MockRequestEngine
import neton.core.module.ModuleInitializer
import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.Test
import kotlin.test.assertTrue

class KtorSignalLifecycleSmokeTest {
    @Test
    fun sigtermStopsReadyCioListener() {
        var ready = false

        Neton.run(emptyArray()) {
            install(RequestEngineComponent) {}
            http { port = 0 }
            modules(NoopModule)
            onReady {
                ready = true
                raise(SIGTERM)
            }
        }

        assertTrue(ready)
    }

    private object RequestEngineComponent : NetonComponent<Unit> {
        override fun defaultConfig() = Unit
        override suspend fun init(ctx: NetonContext, config: Unit) {
            ctx.bind(RequestEngine::class, MockRequestEngine())
        }
    }

    private object NoopModule : ModuleInitializer {
        override val moduleId: String = "ktor-signal-lifecycle-smoke"
        override fun initialize(ctx: NetonContext) = Unit
    }
}
