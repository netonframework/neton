package neton.redis

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.generic.del
import eu.vendeli.rethis.command.generic.exists
import eu.vendeli.rethis.command.generic.expire
import eu.vendeli.rethis.command.generic.keys as rethisKeys
import eu.vendeli.rethis.command.generic.scan
import eu.vendeli.rethis.shared.request.generic.ScanOption
import eu.vendeli.rethis.shared.response.common.ScanResult
import eu.vendeli.rethis.command.hash.hGet
import eu.vendeli.rethis.command.hash.hGetAll
import eu.vendeli.rethis.command.hash.hSet
import eu.vendeli.rethis.command.list.lPop
import eu.vendeli.rethis.command.list.lPush
import eu.vendeli.rethis.command.list.lRange
import eu.vendeli.rethis.command.list.rPush
import eu.vendeli.rethis.command.set.sAdd
import eu.vendeli.rethis.command.set.sMembers
import eu.vendeli.rethis.command.string.decr
import eu.vendeli.rethis.command.string.get
import eu.vendeli.rethis.command.string.incr
import eu.vendeli.rethis.command.scripting.eval
import eu.vendeli.rethis.command.scripting.evalSha
import eu.vendeli.rethis.command.scripting.scriptLoad
import eu.vendeli.rethis.command.string.set
import eu.vendeli.rethis.shared.request.common.FieldValue
import eu.vendeli.rethis.shared.request.string.SetExpire
import eu.vendeli.rethis.shared.request.string.UpsertMode
import eu.vendeli.rethis.types.common.RespVer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import neton.logging.Logger

/**
 * Redis 客户端实现，后端仅支持 re.this（eu.vendeli:rethis:0.3.9）。
 * 业务层不感知底层驱动。可选 logger 用于 WARN（如 KEYS fallback）。
 */
class DefaultRedisClient(config: RedisConfig, private val logger: Logger? = null) : RedisClient {

    /** 全局 key 前缀，如 "neton"；最终 key = keyPrefix + ":" + key（keyPrefix 为空则不加） */
    private val keyPrefix = config.keyPrefix.takeIf { it.isNotBlank() }?.let { "$it:" } ?: ""

    private fun fullKey(key: String): String = if (keyPrefix.isEmpty()) key else keyPrefix + key

    /** 二进制与 String 互转（Latin1，无 Base64）；KMP 兼容 */
    private fun bytesToString(b: ByteArray): String = b.map { it.toInt().and(0xff).toChar() }.toCharArray().concatToString()
    private fun stringToBytes(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private val rt = ReThis(
        host = config.host,
        port = config.port,
        protocol = RespVer.V2
    ) {
        db = config.database
        config.password?.let { auth(it.toCharArray()) }
        maxConnections = config.poolSize
        pool { maxIdleConnections = config.poolSize }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun serialize(value: Any): String = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> value.toString()
    }

    override suspend fun getValue(key: String): String? = rt.get(fullKey(key))

    override suspend fun set(key: String, value: Any, ttl: Duration?) {
        val str = serialize(value)
        val k = fullKey(key)
        if (ttl != null) {
            rt.set(k, str, SetExpire.Ex(ttl))
        } else {
            rt.set(k, str)
        }
    }

    override suspend fun getBytes(key: String): ByteArray? {
        val str = rt.get(fullKey(key)) ?: return null
        return stringToBytes(str)
    }

    override suspend fun setBytes(key: String, value: ByteArray, ttl: Duration?) {
        val str = bytesToString(value)
        val k = fullKey(key)
        if (ttl != null) {
            rt.set(k, str, SetExpire.Ex(ttl))
        } else {
            rt.set(k, str)
        }
    }

    override suspend fun delete(key: String) {
        rt.del(fullKey(key))
    }

    override suspend fun collectScanKeys(matchPattern: String, allowKeysFallback: Boolean, block: suspend (String) -> Unit) {
        val fullPattern = fullKey(matchPattern)
        suspend fun emitLogical(fullKey: String) {
            val logical = if (keyPrefix.isNotEmpty() && fullKey.startsWith(keyPrefix)) fullKey.removePrefix(keyPrefix) else fullKey
            block(logical)
        }
        try {
            var cur = 0L
            do {
                val result = rt.scan(cur, ScanOption.Match(fullPattern), ScanOption.Count(100))
                @Suppress("UNCHECKED_CAST")
                val keyList = (result.keys as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                for (k in keyList) emitLogical(k)
                cur = result.cursor.toLongOrNull() ?: 0L
            } while (cur != 0L)
        } catch (e: Throwable) {
            if (!allowKeysFallback) throw IllegalStateException(
                "neton-redis: SCAN failed or unavailable; allowKeysFallback=false. " +
                "Use KEYS only for temporary fallback with allowKeysClear=true (dangerous in production).",
                e
            )
            logger?.warn("Using KEYS for collectScanKeys (dangerous, avoid in production)", mapOf("pattern" to fullPattern))
            val list = runCatching { rt.rethisKeys(fullPattern) }.getOrNull() ?: emptyList()
            for (k in list) emitLogical(k)
        }
    }

    override suspend fun exists(key: String): Boolean = rt.exists(fullKey(key)) > 0

    override suspend fun expire(key: String, ttl: Duration) {
        rt.expire(fullKey(key), ttl)
    }

    override suspend fun incr(key: String): Long = rt.incr(fullKey(key))

    override suspend fun decr(key: String): Long = rt.decr(fullKey(key))

    override suspend fun hset(key: String, field: String, value: Any) {
        rt.hSet(fullKey(key), FieldValue(field, serialize(value)))
    }

    override suspend fun hget(key: String, field: String): String? = rt.hGet(fullKey(key), field)

    override suspend fun hgetAll(key: String): Map<String, String> =
        rt.hGetAll(fullKey(key)).mapValues { it.value ?: "" }

    override suspend fun lpush(key: String, value: Any) {
        rt.lPush(fullKey(key), serialize(value))
    }

    override suspend fun rpush(key: String, value: Any) {
        rt.rPush(fullKey(key), serialize(value))
    }

    override suspend fun lpop(key: String): String? = rt.lPop(fullKey(key))

    override suspend fun lrange(key: String, start: Int, end: Int): List<String> =
        rt.lRange(fullKey(key), start.toLong(), end.toLong())

    override suspend fun sadd(key: String, value: Any) {
        rt.sAdd(fullKey(key), serialize(value))
    }

    override suspend fun smembers(key: String): Set<String> = rt.sMembers(fullKey(key))

    override suspend fun pipeline(block: RedisPipeline.() -> Unit) {
        val runner = PipelineRunner(this)
        block(runner)
        runner.execute()
    }

    override suspend fun setIfAbsent(key: String, value: String, pxMillis: Long): Boolean {
        val result = rt.set(fullKey(key), value, UpsertMode.NX, SetExpire.Px(pxMillis.milliseconds))
        return result != null
    }

    override suspend fun scriptLoad(script: String): String = rt.scriptLoad(script)

    override suspend fun evalToLong(script: String, keys: List<String>, args: List<String>): Long? {
        val prefixed = keys.map { fullKey(it) }
        val result = rt.eval(script, *prefixed.toTypedArray(), arg = args)
        return (result.value as? Number)?.toLong()
    }

    override suspend fun evalshaToLong(sha: String, keys: List<String>, args: List<String>): Long? {
        return try {
            val prefixed = keys.map { fullKey(it) }
            val result = rt.evalSha(sha, *prefixed.toTypedArray(), arg = args)
            (result.value as? Number)?.toLong()
        } catch (_: Exception) {
            null
        }
    }

    private class PipelineRunner(private val client: RedisClient) : RedisPipeline {
        private val ops = mutableListOf<suspend () -> Unit>()

        override fun set(key: String, value: Any, ttl: Duration?) {
            ops.add { client.set(key, value, ttl) }
        }

        override fun delete(key: String) {
            ops.add { client.delete(key) }
        }

        override fun incr(key: String) {
            ops.add { client.incr(key) }
        }

        override fun decr(key: String) {
            ops.add { client.decr(key) }
        }

        override fun hset(key: String, field: String, value: Any) {
            ops.add { client.hset(key, field, value) }
        }

        override fun lpush(key: String, value: Any) {
            ops.add { client.lpush(key, value) }
        }

        override fun rpush(key: String, value: Any) {
            ops.add { client.rpush(key, value) }
        }

        override fun sadd(key: String, value: Any) {
            ops.add { client.sadd(key, value) }
        }

        suspend fun execute() {
            for (op in ops) op()
        }
    }
}
