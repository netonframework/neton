package neton.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 总线的语义保证。这些断言守的是**钱的正确性**，不是实现细节：
 * SYNC 监听者失败必须让发布方回滚，否则就是"付了钱没解锁"。
 */
class DomainEventBusTest {

    private data class Paid(val id: Long) : DomainEvent
    private data class Refunded(val id: Long) : DomainEvent

    /** 测试用编解码：只验证总线是否走了持久化分支，不关心具体格式。 */
    private val jsonCodec = object : DomainEventCodec {
        override fun encode(event: DomainEvent) = "{}"
        override fun decode(eventType: String, payload: String): DomainEvent? = null
    }

    private sealed interface PayLike : DomainEvent
    private data class SubPaid(val id: Long) : PayLike
    private data class SubClosed(val id: Long) : PayLike

    private class Recorder(
        override val eventType: kotlin.reflect.KClass<Paid> = Paid::class,
        override val mode: DeliveryMode = DeliveryMode.SYNC,
        val fail: Boolean = false,
        override val listenerId: String = "test.recorder",
    ) : DomainEventListener<Paid> {
        val seen = mutableListOf<Long>()
        override suspend fun onEvent(event: Paid) {
            if (fail) throw IllegalStateException("boom")
            seen += event.id
        }
    }

    @Test
    fun deliversToAllListenersOfThatType() = runBlocking {
        val a = Recorder(listenerId = "test.a")
        val b = Recorder(listenerId = "test.b")
        DomainEventBus(listOf(a, b)).publish(Paid(7))
        assertEquals(listOf(7L), a.seen)
        assertEquals(listOf(7L), b.seen)
    }

    @Test
    fun ignoresOtherEventTypes() = runBlocking {
        val a = Recorder()
        DomainEventBus(listOf(a)).publish(Refunded(7))
        assertTrue(a.seen.isEmpty())
    }

    /** SYNC 监听者失败 → 异常上抛，发布方（通常在事务里）随之回滚。 */
    @Test
    fun criticalListenerFailurePropagates() = runBlocking {
        val bus = DomainEventBus(listOf(Recorder(fail = true)))
        assertFailsWith<IllegalStateException> { bus.publish(Paid(1)) }
        Unit
    }

    /** 非SYNC 监听者失败 → 吞掉并记录，不影响发布方，也不影响后续监听者。 */
    @Test
    fun nonCriticalFailureIsIsolated() = runBlocking {
        val errors = mutableListOf<String>()
        val after = Recorder()
        val bus = DomainEventBus(
            listeners = listOf(
                Recorder(mode = DeliveryMode.BEST_EFFORT, fail = true, listenerId = "test.flaky"),
                after,
            ),
            onError = { _, _, e -> errors += e.message ?: "" },
        )
        bus.publish(Paid(9))
        assertEquals(listOf(9L), after.seen, "非SYNC 监听者失败不应挡住后面的")
        assertEquals(listOf("boom"), errors)
    }

    @Test
    fun publishWithoutListenersIsNoop() = runBlocking {
        DomainEventBus().publish(Paid(1))
    }

    /** 事件常是 sealed 层级，订阅父类型必须能收到所有子类型，否则订阅静默失效。 */
    @Test
    fun listenerOnSupertypeReceivesSubtypes() = runBlocking {
        val seen = mutableListOf<String>()
        val parent = object : DomainEventListener<PayLike> {
            override val eventType = PayLike::class
            override val listenerId = "test.parent"
            override suspend fun onEvent(event: PayLike) { seen += event::class.simpleName ?: "?" }
        }
        val bus = DomainEventBus(listOf(parent))
        bus.publish(SubPaid(1))
        bus.publish(SubClosed(2))
        assertEquals(listOf("SubPaid", "SubClosed"), seen)
    }

    /** RETRYABLE：装配了持久化端口时只落库、不当场执行，避免把外部依赖拖进主事务。 */
    @Test
    fun retryableIsPersistedNotExecuted() = runBlocking {
        val appended = mutableListOf<PendingEventRecord>()
        val store = object : DomainEventStore {
            override suspend fun append(record: PendingEventRecord) { appended += record }
            override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long) = emptyList<StoredEventRecord>()
            override suspend fun markDelivered(id: Long, now: Long) {}
            override suspend fun markFailed(id: Long, now: Long, nextAttemptAt: Long?, error: String) {}
        }
        val listener = Recorder(mode = DeliveryMode.RETRYABLE)
        val bus = DomainEventBus(listOf(listener), store = store, codec = jsonCodec)
        bus.publish(Paid(3))
        assertTrue(listener.seen.isEmpty(), "RETRYABLE 不应在发布时同步执行")
        assertEquals(1, appended.size)
        assertEquals(listener.listenerId, appended[0].listenerId)
    }

    @Test
    fun hasAnyListenerReflectsRegistration() {
        assertTrue(DomainEventBus(listOf(Recorder())).hasAnyListener())
        assertTrue(!DomainEventBus().hasAnyListener())
    }

    // ---- 装配期约束 ----

    /**
     * 声明了 RETRYABLE 却没装持久化设施 → 启动期直接失败。
     * 放行的话，运行期会静默退化成同步执行且异常被吞，副作用永久丢失。
     */
    @Test
    fun sealRejectsRetryableWithoutStore() {
        val bus = DomainEventBus(listOf(Recorder(mode = DeliveryMode.RETRYABLE, listenerId = "test.needs-store")))
        val e = assertFailsWith<IllegalStateException> { bus.seal() }
        assertTrue(e.message!!.contains("test.needs-store"), e.message!!)
        assertTrue(e.message!!.contains("DomainEventStore"), e.message!!)
    }

    @Test
    fun sealAcceptsRetryableWithStore() {
        val bus = DomainEventBus(
            listOf(Recorder(mode = DeliveryMode.RETRYABLE)),
            store = NoopStore,
            codec = jsonCodec,
        )
        bus.seal()
    }

    @Test
    fun sealAcceptsBusWithoutRetryableListeners() {
        DomainEventBus(listOf(Recorder())).seal()
    }

    /** 服务期改监听者是数据竞争，表现为偶发漏投递，极难定位——直接禁掉。 */
    @Test
    fun registerAfterSealIsRejected() {
        val bus = DomainEventBus()
        bus.seal()
        assertFailsWith<IllegalStateException> { bus.register(Recorder()) }
    }

    @Test
    fun attachStoreAfterSealIsRejected() {
        val bus = DomainEventBus()
        bus.seal()
        assertFailsWith<IllegalStateException> { bus.attachStore(NoopStore, jsonCodec) }
    }

    @Test
    fun sealIsIdempotent() {
        val bus = DomainEventBus(listOf(Recorder()))
        bus.seal()
        bus.seal()
    }

    // ---- 持久化键 ----

    /** 落库的类型键必须是全限定名：不同包下的同名事件在 simpleName 下会撞成一个键。 */
    @Test
    fun eventTypeKeyIsQualified() {
        val key = Paid(1).eventTypeKey()
        assertTrue(key.contains("."), "应当是全限定名，实际: $key")
        assertTrue(key.endsWith("Paid"), key)
    }

    /**
     * 编解码不认识事件时抛错，而不是改为同步执行。
     * 后者会把「至少一次」静默降级成「至多一次」，还把外部调用拖进主事务。
     */
    @Test
    fun retryableWithUnencodableEventFailsLoudly() = runBlocking {
        val rejectingCodec = object : DomainEventCodec {
            override fun encode(event: DomainEvent): String? = null
            override fun decode(eventType: String, payload: String): DomainEvent? = null
        }
        val bus = DomainEventBus(
            listOf(Recorder(mode = DeliveryMode.RETRYABLE, listenerId = "test.unencodable")),
            store = NoopStore,
            codec = rejectingCodec,
        )
        val e = assertFailsWith<IllegalStateException> { bus.publish(Paid(1)) }
        assertTrue(e.message!!.contains("test.unencodable"), e.message!!)
        Unit
    }

    private object NoopStore : DomainEventStore {
        override suspend fun append(record: PendingEventRecord) {}
        override suspend fun claimDue(now: Long, limit: Int, staleBefore: Long) = emptyList<StoredEventRecord>()
        override suspend fun markDelivered(id: Long, now: Long) {}
        override suspend fun markFailed(id: Long, now: Long, nextAttemptAt: Long?, error: String) {}
    }
}
