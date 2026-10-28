package neton.redis

import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/** Run against an isolated Redis with NETON_TEST_REDIS_PORT set. */
class RedisPipelineIntegrationTest {
    @kotlinx.serialization.Serializable
    data class User(val id: Int, val name: String)

    @Test
    fun typedValuesRoundTripAndCorruptionRefills() = runBlocking {
        val port = getEnv("NETON_TEST_REDIS_PORT")?.toIntOrNull()
        check(getEnv("NETON_TEST_REDIS_REQUIRED") != "true" || port != null)
        if (port == null) return@runBlocking
        val client = DefaultRedisClient(RedisConfig(port = port, keyPrefix = "typed-regression-${kotlin.random.Random.nextLong(1, Long.MAX_VALUE)}"))
        try {
            val user = User(7, "quoted \"name\"")
            var calls = 0
            repeat(2) { assertEquals(user, client.remember("user", 60.seconds) { calls++; user }) }
            assertEquals(1, calls)
            assertEquals(user, client.get<User>("user"))
            client.setTyped("user", user.copy(id = 8), 60.seconds)
            assertEquals(8, client.get<User>("user")!!.id)
            client.set("user", "malformed", 60.seconds)
            assertEquals(user, client.remember("user", 60.seconds) { calls++; user })
            assertEquals(2, calls)
            client.set("number", "not an integer", 60.seconds)
            assertNull(client.get<Int>("number"))
            assertEquals(42, client.remember("number", 60.seconds) { 42 })
            client.setTyped("text", "raw text", 60.seconds)
            assertEquals("raw text", client.getValue("text"))
            client.setTyped("expires", user, 1.milliseconds)
            kotlinx.coroutines.delay(30)
            assertNull(client.get<User>("expires"))
        } finally {
            try { for (key in listOf("user", "number", "text", "expires")) client.delete(key) }
            finally { client.close() }
        }
    }
    @Test
    fun orderedBatchPreservesNamespaceAndReportsErrors() = runBlocking {
        val configuredPort = getEnv("NETON_TEST_REDIS_PORT")?.toIntOrNull()
        check(getEnv("NETON_TEST_REDIS_REQUIRED") != "true" || configuredPort != null) {
            "NETON_TEST_REDIS_REQUIRED=true requires NETON_TEST_REDIS_PORT"
        }
        val port = configuredPort ?: return@runBlocking
        require(port in 1..65535)
        val client = DefaultRedisClient(RedisConfig(port = port, keyPrefix = "pipeline-regression-${kotlin.random.Random.nextLong(1, Long.MAX_VALUE)}"))
        try {
            client.pipeline {}
            // EX would truncate this to zero seconds and Redis would reject it.
            client.set("subsecond-text", "value", 500.milliseconds)
            client.setBytes("subsecond-bytes", byteArrayOf(1), 500.milliseconds)
            client.pipeline {
                set("counter", 1, 60.seconds)
                incr("counter")
                incr("counter")
                decr("counter")
                hset("hash", "field", "value")
                lpush("list", "a")
                rpush("list", "b")
                sadd("set", "member")
                set("remove", "value")
                delete("remove")
            }
            assertEquals("2", client.getValue("counter"))
            assertEquals("value", client.hget("hash", "field"))
            assertEquals(listOf("a", "b"), client.lrange("list", 0, -1))
            assertEquals(setOf("member"), client.smembers("set"))
            assertFalse(client.exists("remove"))
            assertFails { client.pipeline { set("bad", "not-a-number"); incr("bad") } }
            assertEquals("2", client.getValue("counter"))
        } finally {
            try {
                for (key in listOf("counter", "hash", "list", "set", "remove", "bad", "subsecond-text", "subsecond-bytes")) client.delete(key)
            } finally {
                client.close()
            }
        }
        println("NETON_REDIS_PIPELINE_EXECUTED")
    }
}
