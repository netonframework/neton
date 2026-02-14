package neton.redis

/**
 * Redis 连接抽象接口
 * 
 * 定义底层 Redis 协议通信接口
 * 具体实现可以基于不同的网络库或协议实现
 */
interface RedisConnection {
    
    /**
     * 连接状态
     */
    val isConnected: Boolean
    
    /**
     * 连接配置
     */
    val config: RedisConfig
    
    /**
     * 建立连接
     */
    suspend fun connect()
    
    /**
     * 关闭连接
     */
    suspend fun close()
    
    /**
     * 执行 Redis 命令
     * 
     * @param command Redis 命令
     * @param args 命令参数
     * @return 命令执行结果
     */
    suspend fun execute(command: String, vararg args: String): RedisResponse
    
    /**
     * 批量执行命令（Pipeline）
     */
    suspend fun pipeline(commands: List<RedisCommand>): List<RedisResponse>
    
    /**
     * 执行事务
     */
    suspend fun transaction(commands: List<RedisCommand>): List<RedisResponse>
}

/**
 * Redis 命令数据类
 */
data class RedisCommand(
    val command: String,
    val args: List<String> = emptyList()
) {
    constructor(command: String, vararg args: String) : this(command, args.toList())
}

/**
 * Redis 响应封装
 */
sealed class RedisResponse {
    /**
     * 成功响应
     */
    data class Success(val value: Any?) : RedisResponse()
    
    /**
     * 错误响应
     */
    data class Error(val message: String, val cause: Throwable? = null) : RedisResponse()
    
    /**
     * 空响应
     */
    object Null : RedisResponse()
    
    /**
     * 批量响应
     */
    data class Array(val values: List<RedisResponse>) : RedisResponse()
    
    /**
     * 整数响应
     */
    data class Integer(val value: Long) : RedisResponse()
    
    /**
     * 字符串响应
     */
    data class BulkString(val value: String?) : RedisResponse()
    
    /**
     * 简单字符串响应
     */
    data class SimpleString(val value: String) : RedisResponse()
    
    // 工具方法
    fun asString(): String? = when (this) {
        is BulkString -> value
        is SimpleString -> value
        is Success -> value?.toString()
        else -> null
    }
    
    fun asLong(): Long? = when (this) {
        is Integer -> value
        is Success -> (value as? Number)?.toLong()
        is BulkString -> value?.toLongOrNull()
        is SimpleString -> value.toLongOrNull()
        else -> null
    }
    
    fun asBoolean(): Boolean? = when (this) {
        is Integer -> value > 0
        is SimpleString -> value.equals("OK", ignoreCase = true)
        is Success -> value as? Boolean
        else -> null
    }
    
    fun asList(): List<String> = when (this) {
        is Array -> values.mapNotNull { it.asString() }
        else -> emptyList()
    }
    
    fun asMap(): Map<String, String> = when (this) {
        is Array -> {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i < values.size - 1) {
                val key = values[i].asString()
                val value = values[i + 1].asString()
                if (key != null && value != null) {
                    map[key] = value
                }
                i += 2
            }
            map
        }
        else -> emptyMap()
    }
    
    fun isError(): Boolean = this is Error
    
    fun isNull(): Boolean = this is Null || (this is BulkString && value == null)
}
