package neton.redis

import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/** Run against an isolated Redis with NETON_TEST_REDIS_PORT set. */
class RedisPipelineIntegrationTest {
    @Test
    fun orderedBatchPreservesNamespaceAndReportsErrors() = runBlocking {
        val port = getEnv("NETON_TEST_REDIS_PORT")?.toInt() ?: return@runBlocking
        val client = DefaultRedisClient(RedisConfig(port = port, keyPrefix = "pipeline-regression"))
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
            for (key in listOf("counter", "hash", "list", "set", "remove", "bad", "subsecond-text", "subsecond-bytes")) client.delete(key)
            client.close()
        }
    }
}
