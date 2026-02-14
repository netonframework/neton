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
