package neton.redis

import neton.logging.Logger
import neton.logging.emptyFields

/**
 * Redis 连接的模拟实现。
 * 用于开发阶段的测试和验证，不依赖真实的 Redis 服务器。
 * 可选 logger：有则用 Logger，无则静默（不 println）。
 */
class MockRedisConnection(
    override val config: RedisConfig,
    private val logger: Logger? = null
) : RedisConnection {
    
    // 模拟 Redis 数据存储
    private val kvStore = mutableMapOf<String, String>()
    private val hashStore = mutableMapOf<String, MutableMap<String, String>>()
    private val listStore = mutableMapOf<String, MutableList<String>>()
    private val setStore = mutableMapOf<String, MutableSet<String>>()
    private val expirationStore = mutableMapOf<String, Long>()
    
    private var _isConnected = false
    
    override val isConnected: Boolean
        get() = _isConnected
    
    override suspend fun connect() {
        if (config.debug) logger?.debug("MockRedisConnection connecting", emptyFields())
        _isConnected = true
        if (config.debug) logger?.debug("MockRedisConnection connected", emptyFields())
    }

    override suspend fun close() {
        if (config.debug) logger?.debug("MockRedisConnection disconnecting", emptyFields())
        _isConnected = false
        if (config.debug) logger?.debug("MockRedisConnection disconnected", emptyFields())
    }
    
    override suspend fun execute(command: String, vararg args: String): RedisResponse {
        if (!_isConnected) {
            return RedisResponse.Error("Not connected to Redis server")
        }
        
        // 清理过期的键
        cleanupExpiredKeys()
        
        return try {
            executeCommand(command.uppercase(), args.toList())
        } catch (e: Exception) {
            RedisResponse.Error("Command execution failed: ${e.message}", e)
        }
    }
    
    override suspend fun pipeline(commands: List<RedisCommand>): List<RedisResponse> {
        return commands.map { execute(it.command, *it.args.toTypedArray()) }
    }
    
    override suspend fun transaction(commands: List<RedisCommand>): List<RedisResponse> {
        // 简化的事务实现（不支持回滚）
        return pipeline(commands)
    }
    
    // ================================
    // 🎯 命令执行实现
    // ================================
    
    private fun executeCommand(command: String, args: List<String>): RedisResponse {
        if (config.debug) logger?.debug("MockRedis executing", mapOf("command" to command, "args" to args.joinToString(" ")))
        return when (command) {
            // KV 操作
            "GET" -> handleGet(args)
            "SET" -> handleSet(args)
            "DEL" -> handleDel(args)
            "EXISTS" -> handleExists(args)
            "EXPIRE" -> handleExpire(args)
            "TTL" -> handleTtl(args)
            "MGET" -> handleMget(args)
            "MSET" -> handleMset(args)
            
            // Hash 操作
            "HGET" -> handleHget(args)
            "HSET" -> handleHset(args)
            "HDEL" -> handleHdel(args)
            "HGETALL" -> handleHgetall(args)
            "HMSET" -> handleHmset(args)
            
            // List 操作
            "LPUSH" -> handleLpush(args)
            "RPUSH" -> handleRpush(args)
            "LPOP" -> handleLpop(args)
            "RPOP" -> handleRpop(args)
            "LRANGE" -> handleLrange(args)
            "LLEN" -> handleLlen(args)
            
            // Set 操作
            "SADD" -> handleSadd(args)
            "SREM" -> handleSrem(args)
            "SISMEMBER" -> handleSismember(args)
            "SMEMBERS" -> handleSmembers(args)
            "SCARD" -> handleScard(args)
            
            // Stream 操作
            "XADD" -> handleXadd(args)
            "XREAD" -> handleXread(args)
            
            // PubSub 操作
            "PUBLISH" -> handlePublish(args)
            
            else -> RedisResponse.Error("Unknown command: $command")
        }
    }
    
    // ================================
    // 🔑 KV 操作实现
    // ================================
    
    private fun handleGet(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for GET")
        
        val key = args[0]
        val value = kvStore[key]
        return RedisResponse.BulkString(value)
    }
    
    private fun handleSet(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for SET")
        
        val key = args[0]
        val value = args[1]
        
        kvStore[key] = value
        
        // 处理 EX 选项（过期时间）
        if (args.size >= 4 && args[2].uppercase() == "EX") {
            val ttl = args[3].toLongOrNull()
            if (ttl != null) {
                setExpiration(key, ttl)
            }
        }
        
        return RedisResponse.SimpleString("OK")
    }
    
    private fun handleDel(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for DEL")
        
        var deleted = 0
        for (key in args) {
            if (kvStore.remove(key) != null) deleted++
            removeFromAllStores(key)
        }
        
        return RedisResponse.Integer(deleted.toLong())
    }
    
    private fun handleExists(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for EXISTS")
        
        val key = args[0]
        val exists = kvStore.containsKey(key) || 
                    hashStore.containsKey(key) || 
                    listStore.containsKey(key) || 
                    setStore.containsKey(key)
        
        return RedisResponse.Integer(if (exists) 1 else 0)
    }
    
    private fun handleExpire(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for EXPIRE")
        
        val key = args[0]
        val ttl = args[1].toLongOrNull() ?: return RedisResponse.Error("Invalid TTL")
        
        val exists = kvStore.containsKey(key) || 
                    hashStore.containsKey(key) || 
                    listStore.containsKey(key) || 
                    setStore.containsKey(key)
        
        if (exists) {
            setExpiration(key, ttl)
            return RedisResponse.Integer(1)
        }
        
        return RedisResponse.Integer(0)
    }
    
    private fun handleTtl(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for TTL")
        
        val key = args[0]
        val expiration = expirationStore[key]
        
        return if (expiration != null) {
            val remaining = (expiration - getCurrentTimeMillis()) / 1000
            RedisResponse.Integer(if (remaining > 0) remaining else -2)
        } else {
            RedisResponse.Integer(-1) // 没有过期时间
        }
    }
    
    private fun handleMget(args: List<String>): RedisResponse {
        val values = args.map { key ->
            RedisResponse.BulkString(kvStore[key])
        }
        return RedisResponse.Array(values)
    }
    
    private fun handleMset(args: List<String>): RedisResponse {
        if (args.size % 2 != 0) return RedisResponse.Error("Wrong number of arguments for MSET")
        
        for (i in args.indices step 2) {
            val key = args[i]
            val value = args[i + 1]
            kvStore[key] = value
        }
        
        return RedisResponse.SimpleString("OK")
    }
    
    // ================================
    // 🏠 Hash 操作实现
    // ================================
    
    private fun handleHget(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for HGET")
        
        val key = args[0]
        val field = args[1]
        val value = hashStore[key]?.get(field)
        
        return RedisResponse.BulkString(value)
    }
    
    private fun handleHset(args: List<String>): RedisResponse {
        if (args.size < 3) return RedisResponse.Error("Wrong number of arguments for HSET")
        
        val key = args[0]
        val field = args[1]
        val value = args[2]
        
        val hash = hashStore.getOrPut(key) { mutableMapOf() }
        val isNew = !hash.containsKey(field)
        hash[field] = value
        
        return RedisResponse.Integer(if (isNew) 1 else 0)
    }
    
    private fun handleHdel(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for HDEL")
        
        val key = args[0]
        val field = args[1]
        val hash = hashStore[key]
        
        return if (hash?.remove(field) != null) {
            if (hash.isEmpty()) {
                hashStore.remove(key)
            }
            RedisResponse.Integer(1)
        } else {
            RedisResponse.Integer(0)
        }
    }
    
    private fun handleHgetall(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for HGETALL")
        
        val key = args[0]
        val hash = hashStore[key] ?: return RedisResponse.Array(emptyList())
        
        val values = hash.flatMap { (k, v) -> 
            listOf(RedisResponse.BulkString(k), RedisResponse.BulkString(v))
        }
        
        return RedisResponse.Array(values)
    }
    
    private fun handleHmset(args: List<String>): RedisResponse {
        if (args.size < 3 || args.size % 2 == 0) return RedisResponse.Error("Wrong number of arguments for HMSET")
        
        val key = args[0]
        val hash = hashStore.getOrPut(key) { mutableMapOf() }
        
        for (i in 1 until args.size step 2) {
            val field = args[i]
            val value = args[i + 1]
            hash[field] = value
        }
        
        return RedisResponse.SimpleString("OK")
    }
    
    // ================================
    // 📝 List 操作实现
    // ================================
    
    private fun handleLpush(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for LPUSH")
        
        val key = args[0]
        val value = args[1]
        val list = listStore.getOrPut(key) { mutableListOf() }
        
        list.add(0, value)
        return RedisResponse.Integer(list.size.toLong())
    }
    
    private fun handleRpush(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for RPUSH")
        
        val key = args[0]
        val value = args[1]
        val list = listStore.getOrPut(key) { mutableListOf() }
        
        list.add(value)
        return RedisResponse.Integer(list.size.toLong())
    }
    
    private fun handleLpop(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for LPOP")
        
        val key = args[0]
        val list = listStore[key]
        
        return if (list != null && list.isNotEmpty()) {
            val value = list.removeAt(0)
            if (list.isEmpty()) {
                listStore.remove(key)
            }
            RedisResponse.BulkString(value)
        } else {
            RedisResponse.BulkString(null)
        }
    }
    
    private fun handleRpop(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for RPOP")
        
        val key = args[0]
        val list = listStore[key]
        
        return if (list != null && list.isNotEmpty()) {
            val value = list.removeAt(list.size - 1)
            if (list.isEmpty()) {
                listStore.remove(key)
            }
            RedisResponse.BulkString(value)
        } else {
            RedisResponse.BulkString(null)
        }
    }
    
    private fun handleLrange(args: List<String>): RedisResponse {
        if (args.size < 3) return RedisResponse.Error("Wrong number of arguments for LRANGE")
        
        val key = args[0]
        val start = args[1].toLongOrNull() ?: return RedisResponse.Error("Invalid start index")
        val stop = args[2].toLongOrNull() ?: return RedisResponse.Error("Invalid stop index")
        
        val list = listStore[key] ?: return RedisResponse.Array(emptyList())
        
        val actualStart = if (start < 0) maxOf(0, list.size + start.toInt()) else start.toInt()
        val actualStop = if (stop < 0) maxOf(-1, list.size + stop.toInt()) else minOf(list.size - 1, stop.toInt())
        
        val values = if (actualStart <= actualStop) {
            list.subList(actualStart, actualStop + 1).map { RedisResponse.BulkString(it) }
        } else {
            emptyList()
        }
        
        return RedisResponse.Array(values)
    }
    
    private fun handleLlen(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for LLEN")
        
        val key = args[0]
        val list = listStore[key]
        
        return RedisResponse.Integer(list?.size?.toLong() ?: 0)
    }
    
    // ================================
    // 🎯 Set 操作实现
    // ================================
    
    private fun handleSadd(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for SADD")
        
        val key = args[0]
        val member = args[1]
        val set = setStore.getOrPut(key) { mutableSetOf() }
        
        val added = set.add(member)
        return RedisResponse.Integer(if (added) 1 else 0)
    }
    
    private fun handleSrem(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for SREM")
        
        val key = args[0]
        val member = args[1]
        val set = setStore[key]
        
        return if (set?.remove(member) == true) {
            if (set.isEmpty()) {
                setStore.remove(key)
            }
            RedisResponse.Integer(1)
        } else {
            RedisResponse.Integer(0)
        }
    }
    
    private fun handleSismember(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for SISMEMBER")
        
        val key = args[0]
        val member = args[1]
        val set = setStore[key]
        
        return RedisResponse.Integer(if (set?.contains(member) == true) 1 else 0)
    }
    
    private fun handleSmembers(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for SMEMBERS")
        
        val key = args[0]
        val set = setStore[key] ?: return RedisResponse.Array(emptyList())
        
        val values = set.map { RedisResponse.BulkString(it) }
        return RedisResponse.Array(values)
    }
    
    private fun handleScard(args: List<String>): RedisResponse {
        if (args.isEmpty()) return RedisResponse.Error("Wrong number of arguments for SCARD")
        
        val key = args[0]
        val set = setStore[key]
        
        return RedisResponse.Integer(set?.size?.toLong() ?: 0)
    }
    
    // ================================
    // 🌊 Stream 操作实现（简化）
    // ================================
    
    private fun handleXadd(args: List<String>): RedisResponse {
        if (args.size < 4) return RedisResponse.Error("Wrong number of arguments for XADD")
        
        // 简化实现：返回一个模拟的消息 ID
        val timestamp = getCurrentTimeMillis()
        val id = "$timestamp-0"
        
        return RedisResponse.BulkString(id)
    }
    
    private fun handleXread(args: List<String>): RedisResponse {
        // 简化实现：返回空数组
        return RedisResponse.Array(emptyList())
    }
    
    // ================================
    // 🚀 PubSub 操作实现（简化）
    // ================================
    
    private fun handlePublish(args: List<String>): RedisResponse {
        if (args.size < 2) return RedisResponse.Error("Wrong number of arguments for PUBLISH")
        
        // 简化实现：返回 0（没有订阅者）
        return RedisResponse.Integer(0)
    }
    
    // ================================
    // 🛠️ 工具方法
    // ================================
    
    private fun setExpiration(key: String, ttlSeconds: Long) {
        val expirationTime = getCurrentTimeMillis() + (ttlSeconds * 1000)
        expirationStore[key] = expirationTime
    }
    
    private fun cleanupExpiredKeys() {
        val now = getCurrentTimeMillis()
        val expiredKeys = expirationStore.entries
            .filter { it.value <= now }
            .map { it.key }
        
        expiredKeys.forEach { key ->
            expirationStore.remove(key)
            removeFromAllStores(key)
        }
    }
    
    private fun removeFromAllStores(key: String) {
        kvStore.remove(key)
        hashStore.remove(key)
        listStore.remove(key)
        setStore.remove(key)
        expirationStore.remove(key)
    }
    
    private fun clearAllData() {
        kvStore.clear()
        hashStore.clear()
        listStore.clear()
        setStore.clear()
        expirationStore.clear()
    }
    
    // 工具方法：获取当前时间戳（毫秒）
    private fun getCurrentTimeMillis(): Long {
        // 使用 kotlinx-datetime 库获取时间戳
        return kotlin.time.Clock.System.now().toEpochMilliseconds()
    }
} 