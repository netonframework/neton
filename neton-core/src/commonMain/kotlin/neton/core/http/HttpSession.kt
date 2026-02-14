package neton.core.http

/**
 * HTTP 会话接口 - 抽象会话管理
 */
interface HttpSession {
    /**
     * 会话ID
     */
    val id: String
    
    /**
     * 会话创建时间（毫秒时间戳）
     */
    val creationTime: Long
    
    /**
     * 最后访问时间（毫秒时间戳）
     */
    val lastAccessTime: Long
    
    /**
     * 最大非活跃时间间隔（秒）
     */
    var maxInactiveInterval: Int
    
    /**
     * 会话是否为新创建的
     */
    val isNew: Boolean
    
    /**
     * 会话是否有效
     */
    val isValid: Boolean
    
    /**
     * 获取会话属性
     */
    fun getAttribute(name: String): Any?
    
    /**
     * 设置会话属性
     */
    fun setAttribute(name: String, value: Any?)
    
    /**
     * 移除会话属性
     */
    fun removeAttribute(name: String): Any?
    
    /**
     * 获取所有属性名称
     */
    fun getAttributeNames(): Set<String>
    
    /**
     * 检查是否包含指定属性
     */
    fun hasAttribute(name: String): Boolean = getAttribute(name) != null
    
    /**
     * 使会话无效
     */
    fun invalidate()
    
    /**
     * 更新最后访问时间
     */
    fun touch()
    
    /**
     * 检查会话是否过期
     */
    fun isExpired(): Boolean {
        if (!isValid) return true
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val maxInactiveMs = maxInactiveInterval * 1000L
        return (now - lastAccessTime) > maxInactiveMs
    }
    
    /**
     * 获取会话剩余有效时间（秒）
     */
    fun getRemainingTime(): Long {
        if (!isValid) return 0
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val maxInactiveMs = maxInactiveInterval * 1000L
        val remainingMs = maxInactiveMs - (now - lastAccessTime)
        return if (remainingMs > 0) remainingMs / 1000 else 0
    }
    
    /**
     * 清空所有属性
     */
    fun clear() {
        getAttributeNames().forEach { removeAttribute(it) }
    }
    
    /**
     * 获取会话大小（属性数量）
     */
    fun size(): Int = getAttributeNames().size
    
    /**
     * 检查会话是否为空
     */
    fun isEmpty(): Boolean = size() == 0
}

/**
 * 内存会话实现
 */
class MemoryHttpSession(
    override val id: String,
    override val creationTime: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    override var maxInactiveInterval: Int = 1800 // 30分钟
) : HttpSession {
    
    private val attributes = mutableMapOf<String, Any?>()
    private var _lastAccessTime: Long = creationTime
    private var _isValid: Boolean = true
    private var _isNew: Boolean = true
    
    override val lastAccessTime: Long
        get() = _lastAccessTime
    
    override val isNew: Boolean
        get() = _isNew
    
    override val isValid: Boolean
        get() = _isValid && !isExpired()
    
    override fun getAttribute(name: String): Any? {
        checkValid()
        return attributes[name]
    }
    
    override fun setAttribute(name: String, value: Any?) {
        checkValid()
        if (value == null) {
            attributes.remove(name)
        } else {
            attributes[name] = value
        }
    }
    
    override fun removeAttribute(name: String): Any? {
        checkValid()
        return attributes.remove(name)
    }
    
    override fun getAttributeNames(): Set<String> {
        checkValid()
        return attributes.keys.toSet()
    }
    
    override fun invalidate() {
        _isValid = false
        attributes.clear()
    }
    
    override fun touch() {
        if (_isValid) {
            _lastAccessTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
            _isNew = false
        }
    }
    
    private fun checkValid() {
        if (!isValid) {
            throw IllegalStateException("Session has been invalidated")
        }
    }
} 