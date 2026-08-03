package neton.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Config v1.1 契约测试（Neton-Core-Spec 5.3 / 5.4）。
 * 使用 ConfigMerge、ConfigOverrides、ConfigLoader.getConfigValue 正式实现。
 */
class ConfigContractTest {

    private fun get(config: Map<String, Any?>, path: String, default: Any? = null): Any? =
        ConfigLoader.getConfigValue(config, path, default)

    // ---------- 5.4 ENV 规则 ----------

    @Test
    fun envOnlyNetonPrefixParticipates() {
        val env = mapOf(
            "NETON_SERVER__PORT" to "8081",
            "OTHER_VAR" to "ignored"
        )
        val overrides = ConfigOverrides.envToOverrides(env)
        assertEquals("8081", get(overrides, "server.port"))
        assertNull(get(overrides, "other_var"))
    }

    @Test
    fun envDoubleUnderscoreMapsToDotPath() {
        val env = mapOf(
            "NETON_SERVER__PORT" to "8081",
            "NETON_DATABASE__URL" to "postgres://local"
        )
        val overrides = ConfigOverrides.envToOverrides(env)
        assertEquals("8081", get(overrides, "server.port"))
        assertEquals("postgres://local", get(overrides, "database.url"))
    }

    @Test
    fun envScalarOnlyNestedPath() {
        val env = mapOf(
            "NETON_LOGGING__LEVEL" to "DEBUG"
        )
        val overrides = ConfigOverrides.envToOverrides(env)
        assertEquals("DEBUG", get(overrides, "logging.level"))
    }

    // ---------- 5.4 CLI 规则 ----------

    @Test
    fun cliDotPathScalar() {
        val args = arrayOf("--server.port=9090", "--database.url=jdbc:local")
        val overrides = ConfigOverrides.cliToOverrides(args)
        assertEquals("9090", get(overrides, "server.port"))
        assertEquals("jdbc:local", get(overrides, "database.url"))
    }

    @Test
    fun cliOnlyDoubleDashKeyEqualsValue() {
        val args = arrayOf("--server.port=8081", "not-an-arg", "-x=1")
        val overrides = ConfigOverrides.cliToOverrides(args)
        assertEquals("8081", get(overrides, "server.port"))
        assertNull(get(overrides, "x"))
    }

    // ---------- 优先级：CLI > ENV ----------

    @Test
    fun priorityCliOverridesEnv() {
        val base = mapOf<String, Any?>("server" to mapOf("port" to "8080"))
        val envOverrides = ConfigOverrides.envToOverrides(mapOf("NETON_SERVER__PORT" to "8081"))
        val cliOverrides = ConfigOverrides.cliToOverrides(arrayOf("--server.port=9090"))
        val withEnv = ConfigMerge.merge(base, envOverrides)
        val withCli = ConfigMerge.merge(withEnv, cliOverrides)
        assertEquals("9090", get(withCli, "server.port"))
    }

    // ---------- P0.5 优先级链条锁死：CLI > ENV > env.conf > base.conf ----------

    @Test
    fun priorityChainCliOverEnvOverEnvFileOverBase() {
        val base = mapOf<String, Any?>("server" to mapOf("port" to "8080"))
        val envFileOverrides = mapOf<String, Any?>("server" to mapOf("port" to "8081"))
        val envVarOverrides = ConfigOverrides.envToOverrides(mapOf("NETON_SERVER__PORT" to "8082"))
        val cliOverrides = ConfigOverrides.cliToOverrides(arrayOf("--server.port=8083"))
        val step1 = ConfigMerge.merge(base, envFileOverrides)
        val step2 = ConfigMerge.merge(step1, envVarOverrides)
        val finalConfig = ConfigMerge.merge(step2, cliOverrides)
        assertEquals("8083", get(finalConfig, "server.port"))
    }

    // ---------- 5.3 合并：table 深度合并，list 整体覆盖 ----------

    @Test
    fun mergeDeepMergeTables() {
        val base = mapOf<String, Any?>(
            "server" to mapOf("port" to "8080", "host" to "0.0.0.0")
        )
        val override = mapOf<String, Any?>(
            "server" to mapOf("port" to "9090")
        )
        val result = ConfigMerge.merge(base, override)
        assertEquals("9090", get(result, "server.port"))
        assertEquals("0.0.0.0", get(result, "server.host"))
    }

    @Test
    fun mergeListWholeReplace() {
        val base = mapOf<String, Any?>(
            "logging" to mapOf(
                "sinks" to listOf(
                    mapOf("type" to "file", "path" to "/var/log/a.log")
                )
            )
        )
        val override = mapOf<String, Any?>(
            "logging" to mapOf(
                "sinks" to listOf(
                    mapOf("type" to "file", "path" to "/var/log/b.log"),
                    mapOf("type" to "file", "path" to "/var/log/c.log")
                )
            )
        )
        val result = ConfigMerge.merge(base, override)
        val sinks = get(result, "logging.sinks")
        assertTrue(sinks is List<*>)
        assertEquals(2, (sinks as List<*>).size)
        assertEquals("/var/log/b.log", (sinks[0] as? Map<*, *>)?.get("path"))
    }

    // ---------- unknown key：默认忽略，不抛异常（5.4）----------

    @Test
    fun unknownKeyIgnoredKnownKeysStillReadable() {
        val config = mapOf<String, Any?>(
            "server" to mapOf("port" to "8080"),
            "unknown_section" to mapOf("typo_key" to "ignored")
        )
        assertEquals("8080", get(config, "server.port"))
        assertNull(get(config, "server.nonexistent"))
    }

    @Test
    fun unknownKeyDoesNotThrow() {
        val config = mapOf<String, Any?>(
            "server" to mapOf("port" to 8080),
            "typo_secton" to mapOf("anything" to "ignored")
        )
        assertEquals(8080, ConfigLoader.getInt(config, "server.port", ConfigSource.FILE))
    }

    // ---------- getConfigValue 点分路径仅走 table 层级 ----------

    @Test
    fun getConfigValueDotPathTableHierarchy() {
        val config = mapOf<String, Any?>(
            "server" to mapOf("port" to 8080, "nested" to mapOf("a" to "b"))
        )
        assertEquals(8080, get(config, "server.port"))
        assertEquals("b", get(config, "server.nested.a"))
        assertEquals("default", get(config, "server.missing", "default"))
    }

    // ---------- 5.4 类型错误 fail-fast（FILE 与 ENV 两条）----------

    @Test
    fun typeErrorFailFastFileSource() {
        val configFromFile = mapOf<String, Any?>(
            "server" to mapOf("port" to "abc")
        )
        val e = assertFailsWith<ConfigTypeException> {
            ConfigLoader.getInt(configFromFile, "server.port", ConfigSource.FILE)
        }
        assertEquals("server.port", e.path)
        assertEquals("Int", e.expectedType)
        assertEquals(ConfigSource.FILE, e.source)
    }

    @Test
    fun typeErrorFailFastEnvOverrideSource() {
        val envOverrides = ConfigOverrides.envToOverrides(mapOf("NETON_SERVER__PORT" to "abc"))
        val config = ConfigMerge.merge(emptyMap<String, Any?>(), envOverrides)
        val e = assertFailsWith<ConfigTypeException> {
            ConfigLoader.getInt(config, "server.port", ConfigSource.ENV)
        }
        assertEquals("server.port", e.path)
        assertEquals("Int", e.expectedType)
        assertEquals(ConfigSource.ENV, e.source)
    }

    // ---------- 5.4 模块覆盖的命名空间隔离 ----------

    /**
     * 这条是生产事故的最小复现：`.env` 里的 `NETON_DATABASE__URI` 会生成
     * `database = {uri: ...}`。全量合并时它落进**每一个**模块的配置，而 redis 恰好也有
     * 一个 `database` 字段（选库号，Int），拿到 Map 后启动直接崩：
     * `redis.conf: 'database' must be a number, got '{uri=postgresql://...}'`。
     */
    @Test
    fun anotherModulesEnvOverrideDoesNotLeakIntoThisOne() {
        val env = mapOf(
            "NETON_DATABASE__URI" to "postgresql://user:pw@127.0.0.1:5432/app",
            "NETON_REDIS__HOST" to "10.0.0.9",
        )
        val redisFile = mutableMapOf<String, Any?>("host" to "127.0.0.1", "database" to 0)

        val merged = ConfigOverrides.applyModuleOverrides("redis", redisFile, env, emptyArray())

        assertEquals(
            0,
            get(merged, "database"),
            "database 库号被别的模块的 uri 覆盖了——这正是生产启动崩溃的形状",
        )
        assertEquals("10.0.0.9", get(merged, "host"), "本命名空间的覆盖必须生效")
    }

    /** 模块配置是根级平铺的，ENV 的命名空间前缀要剥掉，不能原样留在 map 里。 */
    @Test
    fun moduleOverrideStripsTheNamespacePrefix() {
        val env = mapOf("NETON_PRIVCHAT__SERVER__SERVICE_API_BASE_URL" to "http://127.0.0.1:9090")
        val file = mutableMapOf<String, Any?>(
            "server" to mapOf("service_api_base_url" to "http://old:9090"),
        )

        val merged = ConfigOverrides.applyModuleOverrides("privchat", file, env, emptyArray())

        assertEquals("http://127.0.0.1:9090", get(merged, "server.service_api_base_url"))
        assertNull(get(merged, "privchat.server.service_api_base_url"), "前缀没剥掉，覆盖等于没生效")
    }

    /** CLI 优先于 ENV 的规则在模块作用域内同样成立。 */
    @Test
    fun cliStillWinsOverEnvWithinTheModule() {
        val env = mapOf("NETON_REDIS__PORT" to "6379")
        val merged = ConfigOverrides.applyModuleOverrides(
            "redis",
            mutableMapOf<String, Any?>("port" to 1111),
            env,
            arrayOf("--redis.port=6380"),
        )
        assertEquals("6380", get(merged, "port"))
    }

    /** 没有本模块的覆盖时，文件内容原样保留。 */
    @Test
    fun noOverrideForThisModuleLeavesTheFileAlone() {
        val env = mapOf("NETON_DATABASE__URI" to "postgresql://user:pw@127.0.0.1:5432/app")
        val file = mutableMapOf<String, Any?>("host" to "127.0.0.1", "database" to 3)

        val merged = ConfigOverrides.applyModuleOverrides("redis", file, env, emptyArray())

        assertEquals(3, get(merged, "database"))
        assertEquals("127.0.0.1", get(merged, "host"))
    }

    // ---------- 5.4 解析错误 fail-fast（file + line）----------

    @Test
    fun parseErrorFailFastWithFileAndLine() {
        val invalidToml = """
            [server]
            port = 8080
            [broken
            key = value
        """.trimIndent()
        val e = assertFailsWith<ConfigParseException> {
            TomlParser.parse(invalidToml, "application.conf")
        }
        assertEquals("application.conf", e.sourceName)
        assertTrue(e.lineNumber > 0)
        assertTrue(e.message.contains("application.conf") || e.content.contains("[broken"))
    }
}
