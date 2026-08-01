package neton.redis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三层配置优先级：DSL > redis.conf > 内置默认值。
 *
 * 这里的核心不是「DSL 赢」，而是**「显式设置」必须和「没设置」可区分**。早先的实现拿
 * 「值是否等于默认值」当哨兵，于是 `redis { port = 6379 }` 会被文件里的 6380 覆盖，
 * `redis { debug = false }` 关不掉文件里的 `debug = true`。可空字段才是能表达这件事的模型。
 */
class RedisConfigMergeTest {

    private fun settings(block: RedisSettings.() -> Unit) = RedisSettings().apply(block)
    private fun resolve(dsl: RedisSettings, file: RedisSettings) = resolveRedisConfig(dsl, file)

    // ---- 优先级 ----

    @Test
    fun dslWinsOverFile() {
        val cfg = resolve(
            settings { host = "dsl-host"; port = 7000; keyPrefix = "dsl" },
            settings { host = "file-host"; port = 8000; keyPrefix = "file" },
        )
        assertEquals("dsl-host", cfg.host)
        assertEquals(7000, cfg.port)
        assertEquals("dsl", cfg.keyPrefix)
    }

    @Test
    fun fileWinsOverBuiltinDefaults() {
        val cfg = resolve(RedisSettings(), settings { host = "file-host"; port = 8000; keyPrefix = "file" })
        assertEquals("file-host", cfg.host)
        assertEquals(8000, cfg.port)
        assertEquals("file", cfg.keyPrefix)
    }

    @Test
    fun builtinDefaultsApplyWhenNeitherLayerSets() {
        assertEquals(RedisConfig(), resolve(RedisSettings(), RedisSettings()))
    }

    // ---- 显式设置成「恰好等于默认值」，仍然必须赢过文件 ----

    @Test
    fun explicitDefaultPortStillBeatsFile() {
        val cfg = resolve(settings { port = 6379 }, settings { port = 6380 })
        assertEquals(6379, cfg.port, "DSL 显式写了默认端口，不该被文件覆盖")
    }

    @Test
    fun explicitDefaultHostStillBeatsFile() {
        val cfg = resolve(settings { host = "127.0.0.1" }, settings { host = "10.0.0.1" })
        assertEquals("127.0.0.1", cfg.host)
    }

    @Test
    fun explicitFalseDebugTurnsOffFileDebug() {
        val cfg = resolve(settings { debug = false }, settings { debug = true })
        assertTrue(!cfg.debug, "debug 是覆盖关系，不是 or")
    }

    @Test
    fun fileDebugAppliesWhenDslSaysNothing() {
        assertTrue(resolve(RedisSettings(), settings { debug = true }).debug)
    }

    // ---- password 的类型本身可空，null 不能兼职当「没设置」----

    @Test
    fun explicitNullPasswordClearsTheFileValue() {
        val cfg = resolve(settings { password = null }, settings { password = "from-file" })
        assertNull(cfg.password, "redis { password = null } 必须能清掉文件里的密码")
    }

    @Test
    fun filePasswordAppliesWhenDslNeverTouchesIt() {
        assertEquals("from-file", resolve(RedisSettings(), settings { password = "from-file" }).password)
    }

    @Test
    fun dslPasswordWinsOverFile() {
        assertEquals("from-dsl", resolve(settings { password = "from-dsl" }, settings { password = "from-file" }).password)
    }

    @Test
    fun passwordStaysNullWhenNobodySetsIt() {
        assertNull(resolve(RedisSettings(), RedisSettings()).password)
    }

    // ---- 逐字段覆盖，防止再有字段被漏掉（keyPrefix 曾因此失效）----

    @Test
    fun everyFieldIsCarriedFromFile() {
        val cfg = resolve(
            RedisSettings(),
            settings {
                host = "h"; port = 1; poolSize = 2; database = 3
                password = "p"; timeoutMs = 4; debug = true; keyPrefix = "kp"
            },
        )
        assertEquals(RedisConfig("h", 1, 2, 3, "p", 4, true, "kp"), cfg)
    }

    @Test
    fun everyFieldIsCarriedFromDsl() {
        val cfg = resolve(
            settings {
                host = "h"; port = 1; poolSize = 2; database = 3
                password = "p"; timeoutMs = 4; debug = true; keyPrefix = "kp"
            },
            RedisSettings(),
        )
        assertEquals(RedisConfig("h", 1, 2, 3, "p", 4, true, "kp"), cfg)
    }

    // ---- 文件解析：只填出现过的键，类型不对直接报错 ----

    @Test
    fun fromMapLeavesAbsentKeysUnset() {
        val s = RedisSettings.fromMap(mapOf("host" to "h"))
        assertEquals("h", s.host)
        assertNull(s.port, "没出现的键必须保持 null，否则又变回默认值当哨兵")
        assertNull(s.keyPrefix)
        assertNull(s.debug)
    }

    @Test
    fun fromMapAcceptsMaxConnectionsAlias() {
        assertEquals(32, RedisSettings.fromMap(mapOf("maxConnections" to 32)).poolSize)
    }

    @Test
    fun fromMapReadsTimeoutKey() {
        assertEquals(1500L, RedisSettings.fromMap(mapOf("timeout" to 1500)).timeoutMs)
    }

    @Test
    fun fromMapRejectsNonNumericPort() {
        val e = assertFailsWith<RedisException> { RedisSettings.fromMap(mapOf("port" to "abc")) }
        assertTrue(e.message!!.contains("port"), e.message!!)
    }

    @Test
    fun fromMapRejectsNonBooleanDebug() {
        assertFailsWith<RedisException> { RedisSettings.fromMap(mapOf("debug" to "yes")) }
    }
}
