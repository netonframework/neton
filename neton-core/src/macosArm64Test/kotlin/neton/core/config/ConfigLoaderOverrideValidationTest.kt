@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core.config

import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 真实文件上的 5.4.2：loadModuleConfig 的早拦截 + validateOverrides 的启动末尾校验。 */
class ConfigLoaderOverrideValidationTest {

    private val dir = "/tmp/neton-config-override-test-${getpid()}"
    private val files = mutableListOf<String>()

    private fun write(name: String, content: String) {
        mkdir(dir, 0x1EDu)
        val path = "$dir/$name"
        val f = fopen(path, "w") ?: error("cannot write $path")
        fputs(content, f)
        fclose(f)
        files += path
    }

    @AfterTest
    fun cleanup() {
        files.forEach { remove(it) }
        rmdir(dir)
    }

    private fun fixture() {
        write("application.conf", "[server]\nport = 8080\n")
        write("database.conf", "[default]\ndriver = \"postgresql\"\nuri = \"postgresql://dev/app\"\n[migration]\nhistory_table = \"h\"\n")
        write("geolite.conf", "path = \"/tmp/a.mmdb\"\n")
    }

    @Test
    fun loadingDatabaseConfigRejectsTheUnNamespacedSection() {
        fixture()
        val e = assertFailsWith<ConfigOverrideException> {
            ConfigLoader.loadModuleConfig("database", dir, "test", arrayOf("migrate", "up", "--default.uri=postgresql://e2e/x"))
        }
        assertEquals("--database.default.uri", e.overrides.single().hint)
    }

    @Test
    fun namespacedOverrideLoadsAndValidates() {
        fixture()
        val args = arrayOf("--database.default.uri=postgresql://e2e/x", "--server.port=18290", "--env=test")
        val conf = ConfigLoader.loadModuleConfig("database", dir, "test", args)
        assertEquals("postgresql://e2e/x", ConfigLoader.getConfigValue(conf, "default.uri"))
        ConfigLoader.validateOverrides(dir, "test", args, env = emptyMap())
    }

    @Test
    fun validateFailsOnUnknownCliRootAndAcceptsFileDeclaredNamespace() {
        fixture()
        ConfigLoader.validateOverrides(dir, "test", arrayOf("--geolite.path=/x"), env = emptyMap())
        val e = assertFailsWith<ConfigOverrideException> {
            ConfigLoader.validateOverrides(dir, "test", arrayOf("--nosuch.key=1"), env = emptyMap())
        }
        assertTrue(e.message!!.startsWith("CONFIG_OVERRIDE_UNKNOWN_ROOT"))
    }

    @Test
    fun unknownEnvRootOnlyWarns() {
        fixture()
        ConfigLoader.validateOverrides(dir, "test", emptyArray(), env = mapOf("NETON_SOME_OTHER_HOST_MODULE__KEY" to "v"))
    }
}
