package neton.core.component

/**
 * Neton 组件 - 子系统级能力模块（无内部状态）
 *
 * 生命周期：defaultConfig → block → init → configure → module registration → prepare → start → stop
 * 所有运行时状态必须存入 ctx，不得在 Component 内保存 mutable state。
 *
 * @param C DSL block 的配置类型
 */
interface NetonComponent<C : Any> {

    fun defaultConfig(): C

    /** 初始化：绑定实现到 ctx */
    suspend fun init(ctx: NetonContext, config: C)

    /** 应用级配置贡献阶段；发生在模块注册之前。 */
    suspend fun configure(ctx: NetonContext) {}

    /** 模块注册完成后的运行时定型阶段；可消费最终 registry 并绑定服务。 */
    suspend fun prepare(ctx: NetonContext) {}

    /** 可选：warmup、健康检查、migration 等 */
    suspend fun start(ctx: NetonContext) {}

    /** 可选：关闭连接、释放资源 */
    suspend fun stop(ctx: NetonContext) {}
}
