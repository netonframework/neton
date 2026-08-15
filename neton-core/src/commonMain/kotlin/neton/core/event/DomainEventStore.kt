package neton.core.event

/**
 * 待投递事件的持久化端口。
 *
 * 声明在 core、实现在持久化模块：core 不依赖数据库，没有 [DeliveryMode.RETRYABLE]
 * 监听者的部署不需要装配它。有 RETRYABLE 监听者却没装配时，[DomainEventBus.seal]
 * 在启动期直接失败——不做静默降级。
 *
 * 之所以是"存表 + 轮询"而不是消息中间件：投递记录与发布方的业务写入落在**同一个事务**里，
 * 这是消息中间件给不了的 —— 先写库再发消息会在两者之间留下丢消息的窗口，
 * 先发消息再写库则可能投递一个并未真正发生的事件。事务性 outbox 用一次本地事务消除了这个窗口。
 */
interface DomainEventStore {

    /**
     * 在**调用方当前事务内**记录一条待投递事件。
     *
     * 调用方事务回滚时本记录一并消失，因此不会投递未真正发生的事件。
     */
    suspend fun append(record: PendingEventRecord)

    /**
     * 领取一批到期待投递的事件并标记为处理中。
     *
     * 多实例部署时同一批不应被重复领取，由实现方保证（如行锁或条件更新）。
     *
     * **实现方必须回收滞留的"处理中"记录**：领取之后、[markDelivered] / [markFailed]
     * 之前进程崩溃，这条记录会卡在处理中状态。若没有回收机制，它既不会被再次领取、
     * 也不会报错，事件就此永久静默丢失 —— 而"不丢"正是选用事务性 outbox 的全部理由。
     * 常见做法是给处理中状态设可见性超时，超时未落定即视为可重新领取。
     *
     * @param staleBefore 处理中记录早于该时刻仍未落定，视为滞留，可被重新领取
     */
    suspend fun claimDue(now: Long, limit: Int, staleBefore: Long): List<StoredEventRecord>

    /**
     * 投递成功，标记完成。
     *
     * 必须校验 [claimToken]：领取的行锁只覆盖 [claimDue] 那一条语句，监听者执行期间早已释放。
     * 领取者 A 超时后被 B 重领，A 迟到的落定若不带令牌，会把 B 已经完成的记录改回去。
     * 令牌不匹配时本次落定应当**静默忽略**（返回 false）——它已经不是这条记录的所有者了。
     *
     * @return 是否真的落定了（令牌匹配且状态仍为投递中）
     */
    suspend fun markDelivered(id: Long, claimToken: String, now: Long): Boolean

    /**
     * 投递失败：记录错误并安排下次重试时间；超过上限则置为终态失败，等待人工处理。
     * 同样校验 [claimToken]，理由见 [markDelivered]。
     */
    suspend fun markFailed(id: Long, claimToken: String, now: Long, nextAttemptAt: Long?, error: String): Boolean
}

/**
 * 待写入的事件记录。
 *
 * 不带时间戳：时间由实现方在写入时取。之前这里有个 createdAt，而总线拿不到时钟、
 * 只能恒传 0，落库的每一行 created_at 都是 epoch 0 —— 一个看着有意义、实际永远是
 * 假值的字段，排查积压时反而误导。
 */
data class PendingEventRecord(
    /** 事件类型标识（全限定名），投递时据此还原监听者所需的类型。 */
    val eventType: String,
    /** 目标监听者标识，同一事件的多个监听者各写一条，互不影响重试。 */
    val listenerId: String,
    /** 事件内容的序列化结果。 */
    val payload: String,
)

/** 已落库的事件记录。 */
data class StoredEventRecord(
    val id: Long,
    val eventType: String,
    val listenerId: String,
    val payload: String,
    val attempts: Int,
    /**
     * 本次领取的所有权令牌，落定时必须原样带回。
     *
     * 每次领取都换新值，因此超时被重领后，旧领取者手里的令牌自动失效。
     */
    val claimToken: String,
)

/**
 * 事件内容的编解码。
 *
 * 由应用层实现：只有应用知道自己有哪些事件类型、如何序列化它们。
 * 框架不做序列化选型，也不依赖任何序列化库。
 */
interface DomainEventCodec {

    /** 序列化事件内容；返回 null 表示该事件不支持持久化投递。 */
    fun encode(event: DomainEvent): String?

    /** 还原事件对象；返回 null 表示类型未知或内容已不可解析（调用方据此转终态）。 */
    fun decode(eventType: String, payload: String): DomainEvent?
}
