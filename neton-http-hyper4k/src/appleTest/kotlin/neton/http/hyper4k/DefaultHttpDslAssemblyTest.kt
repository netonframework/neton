@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.http.hyper4k

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.interfaces.RequestEngine
import neton.core.mock.MockRequestEngine
import neton.core.module.ModuleInitializer
import neton.http.http
import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 装配契约：不具名的 `http { }` 必须解析到 [Hyper4kHttpAdapter]。
 *
 * 这条走完整的 Neton.run，测的是重载解析的结果而不是源码长相 —— 默认引擎是靠
 * `neton.http` 包下的一个无参重载建立的，任何人在该包里再加一个 `http` 重载都可能
 * 让它静默漂走，而那种漂移编译期不报错、examples 也照样能编过。
 */
class DefaultHttpDslAssemblyTest {

    @Test
    fun bareHttpDslResolvesToHyper4kAdapter() {
        var resolved: HttpAdapter? = null

        Neton.run(emptyArray()) {
            install(RequestEngineComponent) {}
            http { port = 0 }
            modules(NoopModule)
            onReady {
                resolved = get<HttpAdapter>()
                raise(SIGTERM)
            }
        }

        assertTrue(resolved != null, "onReady 未执行，装配没有走到 READY")
        assertIs<Hyper4kHttpAdapter>(resolved)
    }

    private object RequestEngineComponent : NetonComponent<Unit> {
        override fun defaultConfig() = Unit
        override suspend fun init(ctx: NetonContext, config: Unit) {
            ctx.bind(RequestEngine::class, MockRequestEngine())
        }
    }

    private object NoopModule : ModuleInitializer {
        override val moduleId: String = "hyper4k-default-dsl-assembly"
        override fun initialize(ctx: NetonContext) = Unit
    }
}
