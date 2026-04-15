package neton.routing

import kotlin.concurrent.Volatile

/**
 * 控制器注册表 - 管理控制器实例
 *
 * 线程安全模型：写操作仅在启动阶段（单线程），完成后调用 seal() 发布不可变快照。
 * 请求处理期间所有读操作走 @Volatile snapshot，无锁无竞态。
 */
object ControllerRegistry {
    private val mutable = mutableMapOf<String, Any>()

    @Volatile
    private var snapshot: Map<String, Any> = emptyMap()

    /**
     * 注册控制器实例（仅启动阶段调用）
     */
    fun <T : Any> register(name: String, controller: T) {
        mutable[name] = controller
        // 每次写入后刷新 snapshot，保证即使未显式 seal 也能读到
        snapshot = mutable.toMap()
        RoutingLog.log?.info("routing.controller.registered", mapOf("name" to name))
    }

    /**
     * 获取控制器实例（请求处理期间调用，读不可变快照）
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(name: String): T? {
        return snapshot[name] as? T
    }

    /**
     * 检查控制器是否已注册
     */
    fun has(name: String): Boolean {
        return snapshot.containsKey(name)
    }

    /**
     * 获取所有已注册的控制器名称
     */
    fun getAllControllerNames(): Set<String> {
        return snapshot.keys
    }

    /**
     * 清空所有注册的控制器
     */
    fun clear() {
        mutable.clear()
        snapshot = emptyMap()
    }
} 