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
    suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)? = null)

    suspend fun stop()

    /** 用于启动日志、onStart 回调，由 Adapter 实现提供 */
    fun port(): Int

    /** 适配器名称，用于启动 banner（如 "Ktor"） */
    fun adapterName(): String = "Unknown"
}

/**
 * Mock HTTP 适配器 - 无 HTTP 模块时使用
 */
class MockHttpAdapter(private val mockPort: Int = 8080) : HttpAdapter {

    private var isRunning = false

    override suspend fun start(ctx: NetonContext, onStarted: ((coldStartMs: Long) -> Unit)?) {
        CoreLog.logOrBootstrap().warn("neton.mock.http_adapter", mapOf("hint" to "no HTTP module found"))
        isRunning = true
        onStarted?.invoke(0L)
        while (isRunning) {
            kotlinx.coroutines.delay(1000)
        }
    }

    override suspend fun stop() {
        isRunning = false
    }

    override fun port(): Int = mockPort
    override fun adapterName(): String = "Mock"
} 