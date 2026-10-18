package neton.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Neton-Core-Spec 5.4.2：每个覆盖项都必须被某份配置消费。
 *
 * 事故形状：`--default.uri=...`（本意是 database.conf 的 `[default] uri`）漏了命名空间前缀，
 * 既不落到 application.conf（没有 `default` 段，被当成新增键吞掉）也不落到任何模块，
 * 进程静默连上默认开发库。
 */
class ConfigOverrideConsumptionTest {

    private val appRoots = setOf("server", "security", "logging")
    private val databaseFile = mapOf<String, Any?>(
        "default" to mapOf("driver" to "postgresql", "uri" to "postgresql://dev/app"),
        "migration" to mapOf("history_table" to "neton_schema_history"),
    )
    private val namespaces = mapOf("database" to databaseFile, "redis" to mapOf<String, Any?>("host" to "127.0.0.1"))

    private fun find(args: Array<String> = emptyArray(), env: Map<String, String> = emptyMap()) =
        ConfigOverrides.findUnknownOverrides(env, args, appRoots, namespaces)

    @Test
    fun namespacedModuleOverrideIsConsumedAndApplied() {
        val args = arrayOf("--database.default.uri=postgresql://e2e/throwaway")
        assertEquals(emptyList(), find(args))

        val merged = ConfigOverrides.applyModuleOverrides("database", databaseFile.toMutableMap(), emptyMap(), args)
        assertEquals("postgresql://e2e/throwaway", ConfigLoader.getConfigValue(merged, "default.uri"))
    }

    @Test
    fun unNamespacedModuleSectionFailsWithTheNamespacedHint() {
        val unknown = find(arrayOf("--default.uri=postgresql://e2e/throwaway"))

        assertEquals(1, unknown.size)
        val u = unknown.single()
        assertEquals(ConfigSource.CLI, u.source)
        assertEquals("--default.uri", u.arg)
        assertEquals("default", u.root)
        assertEquals("--database.default.uri", u.hint)

        val message = ConfigOverrideException(unknown).message!!
        assertTrue(message.startsWith(ConfigOverrideException.CODE), message)
        assertTrue("hint=--database.default.uri" in message, message)
    }

    @Test
    fun appLevelOverrideIsConsumed() {
        assertEquals(emptyList(), find(arrayOf("--server.port=18290", "--security.jwt.secretKey=x")))
        // 框架读取的 application 根段即使文件里没写也合法
        assertEquals(
            emptyList(),
            ConfigOverrides.findUnknownOverrides(emptyMap(), arrayOf("--server.port=1", "--http.timeout=5"), emptySet(), emptyMap()),
        )
    }

    @Test
    fun subcommandsFlagsAndFrameworkArgsAreNotOverrides() {
        val args = arrayOf("migrate", "up", "--dry-run", "--tls", "--env=test", "-v", "--database.default.uri=x")
        assertEquals(emptyList(), find(args))
    }

    @Test
    fun unknownRootWithoutAnyOwnerHasNoHint() {
        val u = find(arrayOf("--nosuch.key=1")).single()
        assertEquals("nosuch", u.root)
        assertEquals(null, u.hint)
    }

    @Test
    fun namespaceDeclaredByFileCountsAsKnown() {
        val unknown = ConfigOverrides.findUnknownOverrides(
            emptyMap(), arrayOf("--geolite.path=/tmp/x.mmdb"), appRoots, namespaces,
            namespaceDeclared = { it == "geolite" },
        )
        assertEquals(emptyList(), unknown)
    }

    @Test
    fun unknownEnvRootIsReportedAsEnvWithEnvShapedHint() {
        val env = mapOf(
            "NETON_DEFAULT__URI" to "postgresql://e2e/x",
            "NETON_REDIS__HOST" to "10.0.0.1",
            "NETON_ENV" to "test",
            "NETON_SERVER__PORT" to "1",
            "PATH" to "/usr/bin",
            // 单段 NETON_X 是进程开关/密钥，不是配置路径，不报
            "NETON_IO_DRIVER" to "kqueue",
            "NETON_WALLET_CARD_ENC_KEY" to "k",
        )
        val u = find(env = env).single()
        assertEquals(ConfigSource.ENV, u.source)
        assertEquals("NETON_DEFAULT__URI", u.arg)
        assertEquals("NETON_DATABASE__DEFAULT__URI", u.hint)
    }

    @Test
    fun envWithStrayLeadingUnderscoreIsReported() {
        val u = find(env = mapOf("NETON__SECURITY__JWT__SECRETKEY" to "x")).single()
        assertEquals("_security", u.root)
    }

    @Test
    fun rootMatchingIsCaseInsensitive() {
        assertEquals(emptyList(), find(arrayOf("--Database.default.uri=x", "--SERVER.port=1")))
    }
}
