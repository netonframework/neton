package neton.core.event

import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * 领域事件总线。
 *
 * 由框架在启动最早期绑定，模块在自己的初始化里注册监听者，发布方直接取用。
 * 没有任何消费方时发布是空操作，模块因此可以独立运行、独立测试。
 *
 * **同步派发而非投递到中间件**：发布点通常已处在一个数据库事务中，同步派发让消费方的
 * 写入并入同一事务，成功一起成功、失败一起回滚。改为异步就变成最终一致，需要额外自建
 * 重试与幂等，且中间存在"发布方已生效、消费方尚未生效"的可观察窗口。确实需要脱离主流程
 * 的监听者声明 [DeliveryMode.RETRYABLE]，由 [DomainEventStore] 落库后异步投递 ——
 * 让需要的人付代价，而不是所有人。
 *
 * 三种投递方式的取舍见 [DeliveryMode]。
 *
 * **装配期可变、服务期只读**：[register] 与 [attachStore] 只在启动阶段调用，
 * [seal] 之后再调用会抛错。这不是洁癖 —— 运行期请求跑在多线程调度器上，
 * 一边遍历一边增删监听者是数据竞争，而它表现出来的样子（偶发漏投递）极难定位。
 */
class DomainEventBus(
    listeners: List<DomainEventListener<*>> = emptyList(),
    /** [DeliveryMode.RETRYABLE] 的落库端口，由 [attachStore] 在启动阶段装载。 */
    private var store: DomainEventStore? = null,
    /** 事件内容编解码器，仅 [DeliveryMode.RETRYABLE] 需要。 */
    private var codec: DomainEventCodec? = null,
    /** 非致命异常的记录方式，由装配层注入（core 不依赖日志模块）。 */
    private val onError: (event: DomainEvent, listener: DomainEventListener<*>, error: Throwable) -> Unit =
        { _, _, _ -> },
) {

    private val all: MutableList<DomainEventListener<*>> = listeners.toMutableList()
    private var sealed: Boolean = false

    /**
     * 启动期追加监听者。
     *
     * 总线在模块初始化**之前**就已绑定（发布方那时已持有它），而依赖运行期组件的监听者
     * 此刻还构造不出来。因此留一个注册点，由各模块在自己的启动装配里补上。
     */
    fun register(listener: DomainEventListener<*>) {
        checkMutable("register(${listener.listenerId})")
        all += listener
    }

    /**
     * 装载持久化设施。
     *
     * 总线在模块初始化**之前**就已绑定，而持久化实现依赖数据库等运行期资源，
     * 此时尚不可用。因此留一个装载点，由持久化模块在自己的初始化里补齐。
     */
    fun attachStore(store: DomainEventStore, codec: DomainEventCodec) {
        checkMutable("attachStore")
        this.store = store
        this.codec = codec
    }

    /**
     * 结束装配，转入只读，并校验配置自洽。
     *
     * 声明了 [DeliveryMode.RETRYABLE] 却没有持久化设施，是**装配遗漏**而不是可降级的情况：
     * 该模式的全部意义在于「落库 + 事后重试」，退化成同步执行会把外部调用留在主事务里，
     * 且异常被吞掉后副作用永久丢失 —— 恰好是声明这个模式的人最想避免的两件事。
     * 所以在启动期直接失败，而不是运行期逐条静默降级。
     */
    fun seal() {
        if (sealed) return

        // listenerId 是落库路由键。空白无法路由；重复时 listenerOf 只会命中第一个，
        // 后面的监听者的积压事件永远投给别人——静默地。
        val blank = all.filter { it.listenerId.isBlank() }
        check(blank.isEmpty()) {
            "有 ${blank.size} 个监听者的 listenerId 为空白（eventType=" +
                blank.joinToString { it.eventType.simpleName ?: "?" } + "）。listenerId 是持久化路由键，必须显式声明。"
        }
        val duplicated = all.groupBy { it.listenerId }.filterValues { it.size > 1 }.keys
        check(duplicated.isEmpty()) {
            "listenerId 重复：${duplicated.joinToString()}。落库事件按 listenerId 路由回监听者，" +
                "重复会让后注册的监听者永远收不到自己的积压事件。"
        }

        val needsStore = all.filter { it.mode == DeliveryMode.RETRYABLE }
        if (needsStore.isNotEmpty() && (store == null || codec == null)) {
            val missing = buildList {
                if (store == null) add("DomainEventStore")
                if (codec == null) add("DomainEventCodec")
            }.joinToString(" 和 ")
            error(
                "监听者 ${needsStore.joinToString(", ") { it.listenerId }} 声明了 RETRYABLE，" +
                    "但启动结束时仍缺少 $missing。请装配持久化实现（并在其初始化里调用 attachStore），" +
                    "或把这些监听者改成 SYNC / BEST_EFFORT。"
            )
        }
        sealed = true
    }

    private fun checkMutable(action: String) {
        check(!sealed) { "DomainEventBus 已结束装配，不能再 $action。监听者与持久化设施只能在启动阶段登记。" }
    }

    /**
     * 是否登记了任何监听者。
     *
     * 这里**故意不提供按事件类型的查询**。曾经有过一个 `hasListeners(type)`，用精确类型
     * 相等判定，而 [publish] 用 `isInstance`：订阅父类型的监听者在它那里被判为"无人订阅"，
     * 发布方据此跳过事件构造，事件于是静默丢失 —— 正是 [publish] 注释里警告的那类缺陷。
     *
     * 按类型判定在 Kotlin/Native 上没法正确实现：没有完整反射，拿不到 `isSubclassOf`，
     * 而 `isInstance` 需要一个实例——可实例还没构造，正是要判定的原因。与其留一个
     * 只在精确类型下正确、在密封层级下悄悄骗人的方法，不如不提供。
     *
     * 事件构造真的昂贵时，把昂贵的部分放进监听者，或者让事件持惰性引用。
     */
    fun hasAnyListener(): Boolean = all.isNotEmpty()

    /** 按 [DomainEventListener.listenerId] 取监听者，供异步投递侧回查。 */
    fun listenerOf(listenerId: String): DomainEventListener<*>? =
        all.firstOrNull { it.listenerId == listenerId }

    /**
     * 发布事件：按注册顺序匹配并调用订阅者。
     *
     * 匹配用 `isInstance` 而非精确类型相等 —— 事件常被设计成密封层级，
     * 订阅父类型的监听者应当收到全部子类型。若用精确匹配，这类订阅会**静默失效**：
     * 不报错、也不触发，是最难排查的一类缺陷。
     *
     * [DeliveryMode.SYNC] 监听者抛出的异常原样上抛，发布方若在事务中则一并回滚。
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun publish(event: DomainEvent) {
        for (listener in all) {
            if (!listener.eventType.isInstance(event)) continue

            if (listener.mode == DeliveryMode.RETRYABLE) {
                persist(event, listener)
                continue
            }

            try {
                (listener as DomainEventListener<DomainEvent>).onEvent(event)
            } catch (e: CancellationException) {
                // 取消不是失败：吞掉它会让请求/任务在关闭时无法收敛，还会把取消记成监听者错误
                throw e
            } catch (e: Throwable) {
                if (listener.mode == DeliveryMode.SYNC) throw e
                onError(event, listener, e)
            }
        }
    }

    /**
     * 与发布方同事务落库：发布方回滚则本记录一并消失。
     *
     * 编解码不认识这个事件时抛错而不是改为同步执行：后者会静默地把「至少一次」降级成
     * 「至多一次」，还把外部调用拖进主事务。这是应用层漏注册事件类型，属于配置错误，
     * 让它在测试阶段就炸出来，比在生产上悄悄丢副作用好。
     */
    private suspend fun persist(event: DomainEvent, listener: DomainEventListener<*>) {
        val target = store
            ?: error(
                "监听者 ${listener.listenerId} 声明了 RETRYABLE，但未装配 DomainEventStore。" +
                    "正常情况下 seal() 已在启动期拦下这种配置。"
            )
        val payload = codec?.encode(event)
            ?: error(
                "DomainEventCodec 无法序列化 ${event.eventTypeKey()}，" +
                    "而监听者 ${listener.listenerId} 声明了 RETRYABLE 需要落库投递。" +
                    "请在编解码实现里补上该事件类型。"
            )
        target.append(
            PendingEventRecord(
                eventType = event.eventTypeKey(),
                listenerId = listener.listenerId,
                payload = payload,
            )
        )
    }
}

/**
 * 事件类型的持久化标识。
 *
 * 用全限定名而不是 simpleName：这个字符串会写进数据库、跨进程重启后仍要能解回来，
 * 而不同包下的同名事件（两个 `OrderPaid`）在 simpleName 下会撞成同一个键。
 *
 * 拿不到全限定名（匿名类、局部类）时**抛错**而不是退回 `toString()`：后者在 Native 上
 * 含内存地址，每次进程都不同，落库后必然解不回来。要落库的事件必须是具名顶层/嵌套类。
 */
fun DomainEvent.eventTypeKey(): String =
    this::class.qualifiedName
        ?: error(
            "事件 ${this::class} 没有稳定的全限定名（匿名类或局部类），不能落库投递。" +
                "请把它声明为具名的顶层类或嵌套类。"
        )
