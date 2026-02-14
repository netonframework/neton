package neton.redis

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
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
        Int::class -> s.toIntOrNull() as T
        Long::class -> s.toLongOrNull() as T
        Double::class -> s.toDoubleOrNull() as T
        Float::class -> s.toFloatOrNull() as T
        Boolean::class -> s.toBooleanStrictOrNull() as T
        else -> try { Json.decodeFromString(serializer(), s) } catch (_: Exception) { null }
    }
}

/** 类型安全 remember：redis.remember<User>("user:$id", 5.minutes) { UserTable.get(id) } */
suspend inline fun <reified T> RedisClient.remember(key: String, ttl: Duration, noinline block: suspend () -> T): T {
    val s = getValue(key)
    if (s != null) {
        val decoded: T? = when (T::class) {
            String::class -> s as T
            Int::class -> s.toIntOrNull() as T
            Long::class -> s.toLongOrNull() as T
            Double::class -> s.toDoubleOrNull() as T
            Float::class -> s.toFloatOrNull() as T
            Boolean::class -> s.toBooleanStrictOrNull() as T
            else -> try { Json.decodeFromString(serializer(), s) } catch (_: Exception) { null }
        }
        if (decoded != null) return decoded
    }
    val value = block()
    set(key, value as Any, ttl)
    return value
}
