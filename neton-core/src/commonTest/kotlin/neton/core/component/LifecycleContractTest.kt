package neton.core.component

import kotlinx.coroutines.runBlocking
import neton.core.Neton
import neton.core.http.adapter.HttpAdapter
import neton.core.interfaces.RequestEngine
import neton.core.mock.MockRequestEngine
import neton.core.module.ModuleInitializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LifecycleContractTest {

    @Test
    fun frozenContextRemainsReadableButRejectsRegistration() {
        val ctx = NetonContext(emptyArray())
        ctx.bind(String::class, "ready")

        ctx.freeze()

        assertEquals("ready", ctx.get(String::class))
        assertFailsWith<IllegalStateException> { ctx.bind(Int::class, 1) }
        assertFailsWith<IllegalStateException> {
            ctx.lifecycle.register("late", RecordingLifecycle(mutableListOf(), "late"))
        }
    }

    @Test
    fun lifecycleOwnersStartInOrderAndStopInReverseOrder() = runBlocking {
        val events = mutableListOf<String>()
        val ctx = NetonContext(emptyArray())
        ctx.lifecycle.register("first", RecordingLifecycle(events, "first"))
        ctx.lifecycle.register("second", RecordingLifecycle(events, "second"))

        ctx.lifecycle.startAll()
        ctx.lifecycle.stopStarted { name, error ->
            throw AssertionError("Unexpected stop failure for $name", error)
        }

        assertEquals(
            listOf("first.start", "second.start", "second.stop", "first.stop"),
            events,
        )
    }

    @Test
    fun lifecycleStartFailureStopsEarlierOwnersOnly() = runBlocking {
        val events = mutableListOf<String>()
        val ctx = NetonContext(emptyArray())
        ctx.lifecycle.register("first", RecordingLifecycle(events, "first"))
        ctx.lifecycle.register("failing", object : NetonLifecycle {
            override suspend fun start() {
                events += "failing.start"
                error("owner failed")
            }

            override suspend fun stop() {
                events += "failing.stop"
            }
        })
        ctx.lifecycle.register("never", RecordingLifecycle(events, "never"))

        assertFailsWith<IllegalStateException> { ctx.lifecycle.startAll() }
        ctx.lifecycle.stopStarted { name, error ->
            throw AssertionError("Unexpected stop failure for $name", error)
        }

        assertEquals(listOf("first.start", "failing.start", "first.stop"), events)
    }

    @Test
    fun lifecycleStopFailureDoesNotSkipRemainingOwners() = runBlocking {
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val ctx = NetonContext(emptyArray())
        ctx.lifecycle.register("first", RecordingLifecycle(events, "first"))
        ctx.lifecycle.register("failing", object : NetonLifecycle {
            override suspend fun start() {
                events += "failing.start"
            }

            override suspend fun stop() {
                events += "failing.stop"
                error("stop failed")
            }
        })

        ctx.lifecycle.startAll()
        ctx.lifecycle.stopStarted { name, _ -> failures += name }

        assertEquals(
            listOf("first.start", "failing.start", "failing.stop", "first.stop"),
            events,
        )
        assertEquals(listOf("failing"), failures)
    }

    @Test
    fun applicationBecomesReadyOnlyAfterComponentsAndOwnersStart() {
        val events = mutableListOf<String>()
        val component = RecordingComponent(events)
        val module = RecordingModule(events)

        Neton.run(emptyArray()) {
            install(component) {}
            modules(module)
            onStart {
                events += "application.configure"
                assertEquals(NetonLifecycleState.REGISTERING, get<NetonContext>().lifecycleState)
            }
            onReady {
                events += "application.ready"
                assertEquals(NetonLifecycleState.READY, get<NetonContext>().lifecycleState)
                assertTrue(get<NetonContext>().isFrozen)
            }
        }

        assertEquals(
            listOf(
                "component.init",
                "component.configure",
                "module.initialize",
                "component.prepare",
                "application.configure",
                "component.start",
                "owner.start",
                "http.start",
                "application.ready",
                "http.stop",
                "owner.stop",
                "component.stop",
            ),
            events,
        )
    }

    @Test
    fun moduleFailureEscapesAndInitializedComponentsAreStopped() {
        val events = mutableListOf<String>()
        val component = RecordingComponent(events)
        val failure = IllegalStateException("module failed")

        val thrown = assertFailsWith<IllegalStateException> {
            Neton.run(emptyArray()) {
                install(component) {}
                modules(object : ModuleInitializer {
                    override val moduleId: String = "failing"

                    override fun initialize(ctx: NetonContext) {
                        events += "module.fail"
                        throw failure
                    }
                })
            }
        }

        assertTrue(thrown === failure)
        assertEquals(
            listOf("component.init", "component.configure", "module.fail", "component.stop"),
            events,
        )
    }

    @Test
    fun missingModuleDependencyFailsBeforeHttpStarts() {
        val events = mutableListOf<String>()
        val component = RecordingComponent(events)

        val thrown = assertFailsWith<IllegalStateException> {
            Neton.run(emptyArray()) {
                install(component) {}
                modules(object : ModuleInitializer {
                    override val moduleId: String = "dependent"
                    override val dependsOn: List<String> = listOf("missing")
                    override fun initialize(ctx: NetonContext) = Unit
                })
            }
        }

        assertTrue(thrown.message.orEmpty().contains("missing"))
        assertEquals(
            listOf("component.init", "component.configure", "component.stop"),
            events,
        )
        assertTrue("http.start" !in events)
    }

    private class RecordingComponent(
        private val events: MutableList<String>,
    ) : NetonComponent<Unit> {
        private lateinit var adapter: RecordingHttpAdapter

        override fun defaultConfig() = Unit

        override suspend fun init(ctx: NetonContext, config: Unit) {
            events += "component.init"
            adapter = RecordingHttpAdapter(events)
            ctx.bind(RequestEngine::class, MockRequestEngine())
            ctx.bind(HttpAdapter::class, adapter)
        }

        override suspend fun configure(ctx: NetonContext) {
            events += "component.configure"
        }

        override suspend fun prepare(ctx: NetonContext) {
            events += "component.prepare"
        }

        override suspend fun start(ctx: NetonContext) {
            assertEquals(NetonLifecycleState.STARTING, ctx.lifecycleState)
            assertTrue(ctx.isFrozen)
            events += "component.start"
        }

        override suspend fun stop(ctx: NetonContext) {
            events += "component.stop"
        }
    }

    private class RecordingHttpAdapter(
        private val events: MutableList<String>,
    ) : HttpAdapter {
        override val capabilities = emptySet<neton.core.http.adapter.HttpCapability>()

        override suspend fun start(
            ctx: NetonContext,
            onStarted: (suspend (coldStartMs: Long) -> Unit)?,
        ) {
            assertEquals(NetonLifecycleState.STARTING, ctx.lifecycleState)
            events += "http.start"
            onStarted?.invoke(1L)
        }

        override suspend fun stop() {
            events += "http.stop"
        }

        override fun port(): Int = 8080

        override fun adapterName(): String = "Recording"
    }

    private class RecordingModule(
        private val events: MutableList<String>,
    ) : ModuleInitializer {
        override val moduleId: String = "recording"

        override fun initialize(ctx: NetonContext) {
            assertEquals(NetonLifecycleState.REGISTERING, ctx.lifecycleState)
            events += "module.initialize"
            ctx.lifecycle.register("owner", RecordingLifecycle(events, "owner"))
        }
    }

    private class RecordingLifecycle(
        private val events: MutableList<String>,
        private val name: String,
    ) : NetonLifecycle {
        override suspend fun start() {
            events += "$name.start"
        }

        override suspend fun stop() {
            events += "$name.stop"
        }
    }
}
