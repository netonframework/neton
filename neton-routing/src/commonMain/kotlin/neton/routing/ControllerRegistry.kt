package neton.routing

/**
 * 控制器注册表 - 管理控制器实例
 */
object ControllerRegistry {
    private val controllers = mutableMapOf<String, Any>()
    
    /**
     * 注册控制器实例
     */
    fun <T : Any> register(name: String, controller: T) {
        controllers[name] = controller
        RoutingLog.log?.info("routing.controller.registered", mapOf("name" to name))
    }
    
    /**
     * 获取控制器实例
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(name: String): T? {
        return controllers[name] as? T
    }
    
    /**
     * 检查控制器是否已注册
     */
    fun has(name: String): Boolean {
        return controllers.containsKey(name)
    }
    
    /**
     * 获取所有已注册的控制器名称
     */
    fun getAllControllerNames(): Set<String> {
        return controllers.keys.toSet()
    }
    
    /**
     * 清空所有注册的控制器
     */
    fun clear() {
        controllers.clear()
    }
} 