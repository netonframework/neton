package neton.core.event

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * 按事件类登记序列化器的 [DomainEventCodec]。
 *
 * 可重试投递（[DeliveryMode.RETRYABLE]）需要把事件落库再解回来；与其让每个应用手写一个
 * 认识全部事件类型的编解码器（每加一个模块都要回头改它），不如由**发布事件的模块**在自己的
 * 初始化里登记：`codec.register(PayOrderPaidEvent::class, PayOrderPaidEvent.serializer())`。
 *
 * 落库键与 [eventTypeKey] 一致，是事件类的全限定名。未登记的事件 [encode] 返回 null，
 * 总线因此会在落库时报错 —— 这是有意的：要异步投递的事件必须先登记，不能静默丢失。
 *
 * 装配期登记，服务期只读；与 [DomainEventBus] 相同，不在运行期增删。
 */
class SerializerDomainEventCodec(
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : DomainEventCodec {
    private val byType = mutableMapOf<String, KSerializer<out DomainEvent>>()
    private var sealed = false

    fun <E : DomainEvent> register(type: KClass<E>, serializer: KSerializer<E>) {
        check(!sealed) { "Event codec registration is sealed" }
        val key = type.qualifiedName ?: error("事件类 $type 没有全限定名，不能登记为可落库事件")
        val previous = byType[key]
        require(previous == null || previous == serializer) { "事件 $key 重复登记了不同的序列化器" }
        byType[key] = serializer
    }

    fun registered(): Set<String> = byType.keys.toSet()

    override fun seal() { sealed = true }

    @Suppress("UNCHECKED_CAST")
    override fun encode(event: DomainEvent): String? {
        val serializer = byType[event.eventTypeKey()] as KSerializer<DomainEvent>? ?: return null
        return json.encodeToString(serializer, event)
    }

    override fun decode(eventType: String, payload: String): DomainEvent? {
        val serializer = byType[eventType] ?: return null
        return json.decodeFromString(serializer, payload)
    }
}
