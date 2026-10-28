package neton.redis

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.SerializationException
import neton.core.component.NetonContext
import kotlin.time.Duration

/** 从应用上下文获取 Redis 客户端（需先 install redis { }） */
fun NetonContext.getRedis(): RedisClient = get<RedisClient>()

/** 原始取值 */
suspend fun RedisClient.get(key: String): String? = getValue(key)

/** 类型安全取值：redis.get<User>("user:1") */
suspend inline fun <reified T> RedisClient.get(key: String): T? {
    val s = getValue(key) ?: return null
    return when (T::class) {
        String::class -> s as T
        Int::class -> s.toIntOrNull() as T?
        Long::class -> s.toLongOrNull() as T?
        Double::class -> s.toDoubleOrNull() as T?
        Float::class -> s.toFloatOrNull() as T?
        Boolean::class -> s.toBooleanStrictOrNull() as T?
        else -> try {
            Json.decodeFromString(serializer(), s)
        } catch (_: SerializationException) {
            // A corrupt value is a miss. Never log keys or decoder input fragments.
            null
        }
    }
}

/** Typed counterpart of get<T>. Raw set(Any) retains its legacy string conversion. */
suspend inline fun <reified T> RedisClient.setTyped(key: String, value: T, ttl: Duration? = null) {
    val encoded = when (T::class) {
        String::class, Int::class, Long::class, Double::class, Float::class, Boolean::class -> value.toString()
        else -> Json.encodeToString(serializer<T>(), value)
    }
    set(key, encoded, ttl)
}

/** 类型安全 remember：redis.remember<User>("user:$id", 5.minutes) { UserTable.get(id) } */
suspend inline fun <reified T> RedisClient.remember(key: String, ttl: Duration, noinline block: suspend () -> T): T {
    get<T>(key)?.let { return it }
    val value = block()
    setTyped(key, value, ttl)
    return value
}
