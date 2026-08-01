package neton.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * `cache { }` 安装层契约：DSL 声明、cache.conf 解析、DSL 覆盖文件。
 *
 * 这一层在 1.0 之前完全不存在——`@Cacheable` 家族是公开注解，但没有任何途径把
 * CacheManager 绑进 NetonContext，用了必 500。测试锁住装配语义。
 */
class CacheComponentTest {

    // ---- DSL ----

    @Test
    fun dslDeclaresNamedCaches() {
        val settings = CacheSettings().apply {
            cache("user") {
                ttl = 10.minutes
                maxSize = 5000
            }
            cache("session")
        }
        assertEquals(setOf("user", "session"), settings.defined.keys)

        val user = settings.defined.getValue("user")
        assertEquals("user", user.name)
        assertEquals(10.minutes, user.ttl)
        assertEquals(5000, user.maxSize)
        // 未设置的字段保持默认
        assertEquals(CacheCodecKind.PROTOBUF, user.codec)
        assertTrue(user.enableL1)
        assertEquals(false, user.allowKeysClear)
    }

    @Test
    fun dslRejectsBlankName() {
        assertFailsWith<IllegalArgumentException> {
            CacheSettings().cache("  ") {}
        }
    }

    @Test
    fun dslCanDisableL1AndAllowKeysClear() {
        val settings = CacheSettings().apply {
            cache("raw") {
                enableL1 = false
                allowKeysClear = true
                maxSize = null
                nullTtl = 30.minutes
            }
        }
        val c = settings.defined.getValue("raw")
        assertEquals(false, c.enableL1)
        assertTrue(c.allowKeysClear)
        assertNull(c.maxSize)
        assertEquals(30.minutes, c.nullTtl)
    }

    // ---- cache.conf 解析 ----

    @Test
    fun fileConfigParsesAllFields() {
        val c = CacheComponent.cacheConfigFromMap(
            "user",
            mapOf(
                "ttlMs" to 600_000L,
                "nullTtlMs" to 60_000L,
                "maxSize" to 5000,
                "enableL1" to false,
                "codec" to "JSON",
                "allowKeysClear" to true,
            ),
        )
        assertEquals("user", c.name)
        assertEquals(600_000.milliseconds, c.ttl)
        assertEquals(60_000.milliseconds, c.nullTtl)
        assertEquals(5000, c.maxSize)
        assertEquals(false, c.enableL1)
        assertEquals(CacheCodecKind.JSON, c.codec)
        assertTrue(c.allowKeysClear)
    }

    @Test
    fun fileConfigFallsBackToDefaults() {
        val defaults = CacheConfig("empty")
        val c = CacheComponent.cacheConfigFromMap("empty", emptyMap())
        assertEquals(defaults, c)
    }

    @Test
    fun fileConfigCodecIsCaseInsensitive() {
        val c = CacheComponent.cacheConfigFromMap("x", mapOf("codec" to "protobuf"))
        assertEquals(CacheCodecKind.PROTOBUF, c.codec)
    }

    @Test
    fun fileConfigRejectsUnknownCodec() {
        // 不静默回落默认值——配错 codec 会让 L2 读写格式不一致
        val e = assertFailsWith<IllegalArgumentException> {
            CacheComponent.cacheConfigFromMap("x", mapOf("codec" to "msgpack"))
        }
        assertTrue("msgpack" in (e.message ?: ""))
    }

    @Test
    fun fileConfigMaxSizeZeroOrNegativeMeansUnbounded() {
        // TOML 没有 null，用 <= 0 表达「不限制条目、仅按 TTL 淘汰」
        assertNull(CacheComponent.cacheConfigFromMap("x", mapOf("maxSize" to 0)).maxSize)
        assertNull(CacheComponent.cacheConfigFromMap("x", mapOf("maxSize" to -1)).maxSize)
        assertEquals(1000, CacheComponent.cacheConfigFromMap("x", emptyMap()).maxSize)
    }

    @Test
    fun fileConfigRejectsNonNumericFields() {
        // 不静默回落默认值：maxSize 写错会变成无界内存增长，ttl 写错会变成默认 1h
        assertFailsWith<IllegalArgumentException> {
            CacheComponent.cacheConfigFromMap("x", mapOf("maxSize" to "lots"))
        }
        assertFailsWith<IllegalArgumentException> {
            CacheComponent.cacheConfigFromMap("x", mapOf("ttlMs" to "10m"))
        }
        assertFailsWith<IllegalArgumentException> {
            CacheComponent.cacheConfigFromMap("x", mapOf("enableL1" to "yes"))
        }
    }

    // ---- 合并优先级 ----

    @Test
    fun dslOverridesFileForSameName() {
        val fromFile = mapOf(
            "user" to CacheComponent.cacheConfigFromMap("user", mapOf("ttlMs" to 1_000L)),
            "session" to CacheComponent.cacheConfigFromMap("session", mapOf("ttlMs" to 2_000L)),
        )
        val fromDsl = CacheSettings().apply {
            cache("user") { ttl = 10.minutes }
        }.defined

        val effective = fromFile + fromDsl

        assertEquals(setOf("user", "session"), effective.keys)
        // 同名以 DSL 为准
        assertEquals(10.minutes, effective.getValue("user").ttl)
        // 仅文件声明的保留
        assertEquals(2_000.milliseconds, effective.getValue("session").ttl)
    }
}
