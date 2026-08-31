package neton.core.event

import kotlinx.coroutines.runBlocking
import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.core.mock.MockRequestEngine
import neton.core.interfaces.RequestEngine
import neton.core.module.ModuleInitializer
import neton.logging.Fields
import neton.logging.Logger
import neton.logging.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 框架装配层面的事件总线契约：走完整的 `Neton.run`，验证框架绑定的那个总线实例
 * 真的把 BEST_EFFORT 失败记录下来了。单测 `DomainEventBus` 时会注入自己的回调，
 * 覆盖不到"框架默认给的回调是不是空的"这一层——而那正是"契约说记录、运行时没记录"的缝。
 */
class EventBusAssemblyTest {

    private data class Ping(val id: Long) : DomainEvent

    private class RecordingLogger : Logger {
        val warns = mutableListOf<Triple<String, Fields, Throwable?>>()
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) { warns += Triple(msg, fields, cause) }
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private class RecordingLoggerFactory(val logger: RecordingLogger) : LoggerFactory {
        override fun get(name: String): Logger = logger
    }

    /** 最小 HTTP 桩，让 Neton.run 走到 ready 再停。 */
    private class StubHttpComponent : NetonComponent<Unit> {
        override fun defaultConfig() = Unit
        override suspend fun init(ctx: NetonContext, config: Unit) {
            ctx.bind(RequestEngine::class, MockRequestEngine())
            ctx.bind(HttpAdapter::class, object : HttpAdapter {
                override suspend fun start(ctx: NetonContext, onStarted: (suspend (Long) -> Unit)?) { onStarted?.invoke(1L) }
                override suspend fun stop() {}
                override fun port() = 0
                override val capabilities = emptySet<neton.core.http.adapter.HttpCapability>()
            })
        }
    }

    @Test
    fun frameworkBoundBusRecordsBestEffortFailures() {
        val logger = RecordingLogger()
        var busSeen: DomainEventBus? = null

        Neton.run(emptyArray()) {
            bind(LoggerFactory::class, RecordingLoggerFactory(logger))
            install(StubHttpComponent()) {}
            modules(object : ModuleInitializer {
                override val moduleId = "assembly-test"
                override fun initialize(ctx: NetonContext) {
                    val bus = ctx.get(DomainEventBus::class)
                    busSeen = bus
                    bus.register(object : DomainEventListener<Ping> {
                        override val eventType = Ping::class
                        override val listenerId = "assembly-test.flaky"
                        override val mode = DeliveryMode.BEST_EFFORT
                        override suspend fun onEvent(event: Ping) = throw IllegalStateException("flaky listener")
                    })
                }
            })
            onReady {
                runBlocking { get<DomainEventBus>().publish(Ping(1)) }
            }
        }

        assertNotNull(busSeen, "模块初始化时框架绑定的总线必须已存在")

        val hit = logger.warns.firstOrNull { it.first == "event.listener.failed" }
        assertNotNull(hit, "BEST_EFFORT 失败必须被记录，实际 warn 列表: ${logger.warns.map { it.first }}")
        assertEquals("assembly-test.flaky", hit.second["listener"])
        assertEquals(Ping::class.qualifiedName, hit.second["event"])
        assertEquals("BEST_EFFORT", hit.second["mode"])
        assertTrue(hit.third is IllegalStateException)
        assertEquals("flaky listener", hit.third?.message)
    }
}
