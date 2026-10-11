package neton.cache

import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
import neton.cache.internal.*
import neton.redis.RedisClient
import neton.redis.RedisPipeline
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CacheRegressionTest {
    @Test
    fun hotReadsAndUpdatesDoNotEvictHotEntries() = runBlocking {
        val cache = L1Cache<String, String>(2, 10.seconds)
        cache.put("hot", "a", 10.seconds)
        cache.put("cold", "b", 10.seconds)
        assertEquals("a", cache.get("hot"))
        cache.put("new", "c", 10.seconds)
        assertNull(cache.get("cold"))
        cache.put("hot", "updated", 10.seconds)
        assertEquals("c", cache.get("new"))
        assertEquals("updated", cache.get("hot"))
    }

    @Test
    fun entryExpiresAtDeadline() = runBlocking {
        var now = 0L
        val cache = L1Cache<String, String>(2, 1.seconds) { now }
        cache.put("key", "value", 1.seconds)
        now = 1000
        assertNull(cache.get("key"))
    }

    @Test
    fun clearKeepsNamespaceAndBatchesDeletes() = runBlocking {
        val redis = MemoryRedis()
        repeat(600) { redis.values["cache:user:$it"] = byteArrayOf(1) }
        redis.values["42"] = byteArrayOf(2)
        redis.values["cache:other:42"] = byteArrayOf(3)
        RedisCacheBacking(redis, "cache:user", false).clear()
        assertEquals(setOf("42", "cache:other:42"), redis.values.keys)
        assertEquals(listOf(256, 256, 88), redis.batches)
    }

    @Test
    fun negativeCacheSkipsLoaderAndL2ReadsDoNotExtendTtl() = runBlocking {
        val redis = MemoryRedis()
        val l1 = L1Cache<String, String>(10, 10.seconds)
        val cache = TwoLevelCache(CacheConfig(name = "user", nullTtl = 1.seconds), l1,
            RedisCacheBacking(redis, "cache:user", false), String.serializer())
        var loads = 0
        repeat(2) { assertNull(cache.getOrPut("absent") { loads++; null }) }
        assertEquals(1, loads)
        cache.put("present", "value")
        l1.clear()
        assertEquals("value", cache.get("present"))
        redis.values.remove("cache:user:present")
        assertNull(cache.get("present"))
    }

    @Test
    fun cancelledFlightDoesNotPoisonNextLoad() = runBlocking {
        val cache = TwoLevelCache(CacheConfig(name = "user"), L1Cache<String, String>(10, 10.seconds),
            RedisCacheBacking(MemoryRedis(), "cache:user", false), String.serializer())
        val entered = CompletableDeferred<Unit>()
        val flight = launch { cache.getOrPut("key") { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        flight.cancelAndJoin()
        assertEquals("retry", withTimeout(1000) { cache.getOrPut("key") { "retry" } })
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedLoad() = runBlocking {
        val cache = TwoLevelCache(CacheConfig(name = "user"), L1Cache<String, String>(10, 10.seconds),
            RedisCacheBacking(MemoryRedis(), "cache:user", false), String.serializer())
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrPut("key") { loads++; release.await(); "value" }
        }
        val waiters = List(20) { async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrPut("key") { loads++; "unexpected" }
        } }
        waiters.first().cancelAndJoin()
        release.complete(Unit)
        assertEquals("value", owner.await())
        waiters.drop(1).forEach { assertEquals("value", it.await()) }
        assertEquals(1, loads)
    }
}

private class MemoryRedis : RedisClient {
    val values = mutableMapOf<String, ByteArray>()
    val batches = mutableListOf<Int>()
    override suspend fun close() {}
    override suspend fun getBytes(key: String) = values[key]
    override suspend fun setBytes(key: String, value: ByteArray, ttl: Duration?) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
    override suspend fun collectScanKeys(matchPattern: String, allowKeysFallback: Boolean, block: suspend (String) -> Unit) {
        for (key in values.keys.toList()) block(key)
    }
    override suspend fun pipeline(block: RedisPipeline.() -> Unit) {
        val keys = mutableListOf<String>()
        block(object : RedisPipeline {
            override fun delete(key: String) { keys += key }
            override fun set(key: String, value: Any, ttl: Duration?) = error("unused")
            override fun incr(key: String) = error("unused")
            override fun decr(key: String) = error("unused")
            override fun hset(key: String, field: String, value: Any) = error("unused")
            override fun lpush(key: String, value: Any) = error("unused")
            override fun rpush(key: String, value: Any) = error("unused")
            override fun sadd(key: String, value: Any) = error("unused")
        })
        batches += keys.size
        keys.forEach { delete(it) }
    }
    override suspend fun getValue(key: String): String? = error("unused")
    override suspend fun set(key: String, value: Any, ttl: Duration?) = error("unused")
    override suspend fun exists(key: String): Boolean = error("unused")
    override suspend fun expire(key: String, ttl: Duration) = error("unused")
    override suspend fun incr(key: String): Long = error("unused")
    override suspend fun decr(key: String): Long = error("unused")
    override suspend fun hset(key: String, field: String, value: Any) = error("unused")
    override suspend fun hget(key: String, field: String): String? = error("unused")
    override suspend fun hgetAll(key: String): Map<String, String> = error("unused")
    override suspend fun lpush(key: String, value: Any) = error("unused")
    override suspend fun rpush(key: String, value: Any) = error("unused")
    override suspend fun lpop(key: String): String? = error("unused")
    override suspend fun lrange(key: String, start: Int, end: Int): List<String> = error("unused")
    override suspend fun sadd(key: String, value: Any) = error("unused")
    override suspend fun smembers(key: String): Set<String> = error("unused")
    override suspend fun info(section: String?): String? = error("unused")
    override suspend fun dbSize(): Long = error("unused")
    override suspend fun setIfAbsent(key: String, value: String, pxMillis: Long): Boolean = error("unused")
    override suspend fun scriptLoad(script: String): String = error("unused")
    override suspend fun evalToLong(script: String, keys: List<String>, args: List<String>): Long? = error("unused")
    override suspend fun evalshaToLong(sha: String, keys: List<String>, args: List<String>): Long? = error("unused")
}
