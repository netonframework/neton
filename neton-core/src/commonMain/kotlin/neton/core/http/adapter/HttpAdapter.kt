package neton.core.http.adapter

import neton.core.CoreLog
import neton.core.component.NetonContext

/**
 * HTTP 适配器接口 - Core 只定义抽象，port/config 归 Component。
 * Adapter 在 start(ctx) 内从 ctx 取 RequestEngine，内部持有 port。
 */
interface HttpAdapter {

    /**
     * 启动服务器（从 ctx 获取 RequestEngine，port 在 Adapter 内部）。
     * @param onStarted 启动成功后由 Adapter 调用，传入 coldStartMs（毫秒），框架层用于打印 banner
     */
    suspend fun start(ctx: NetonContext, onStarted: (suspend (coldStartMs: Long) -> Unit)? = null)

    suspend fun stop()

    /** 用于启动日志、onStart 回调，由 Adapter 实现提供 */
    fun port(): Int

    /** 适配器名称，用于启动 banner（如 "Ktor"） */
    fun adapterName(): String = "Unknown"

    /**
     * 本引擎**实际具备**的能力（spec http-engine-capabilities §2.2）。
     *
     * **刻意不给默认值**：默认空集会让新 Adapter 悄悄"什么都不支持"，
     * 默认全集会让它悄悄"什么都支持"，两种都把问题推迟到运行时。
     * 强制作者逐项回答。
     *
     * **实现之前不许声明**。声明一个没做完的能力，比不声明危险得多——
     * 启动校验会放行，然后在运行时以"行为不对但不报错"的形式暴露。
     */
    val capabilities: Set<HttpCapability>
}
