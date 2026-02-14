package neton.logging.internal

import neton.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LoggingConfig 解析契约测试（Phase 1）。
 */
class LoggingConfigParserContractTest {

    @Test
    fun parsesSinksWithRouteAndLevels() {
        val config = mapOf(
            "level" to "INFO",
            "sinks" to listOf(
                mapOf(
                    "name" to "access",
                    "file" to "logs/access.log",
                    "levels" to "INFO",
                    "route" to "http.access"
                ),
                mapOf(
                    "name" to "error",
                    "file" to "logs/error.log",
                    "levels" to "ERROR,WARN"
                ),
                mapOf(
                    "name" to "all",
                    "file" to "logs/all.log",
                    "levels" to "ALL"
                )
            )
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null LoggingConfig")
        assertEquals(LogLevel.INFO, result.minLevel)
        assertTrue(result.rules.size >= 3)
        val accessRule = result.rules.find { it.name == "access" }
        assertNotNull(accessRule)
        assertEquals("http.access", accessRule.msgEquals)
        assertEquals(setOf(LogLevel.INFO), accessRule.levels)
        assertTrue(accessRule.sinks.any { it is SinkSpec.File && it.path == "logs/access.log" })
    }

    @Test
    fun nullOrEmptySinks_returnsNull() {
        assertEquals(null, parseLoggingConfig(null))
        val empty = parseLoggingConfig(mapOf("level" to "DEBUG", "sinks" to emptyList<Map<String, Any?>>()))
        assertEquals(null, empty)
    }

    @Test
    fun respectsMinLevel() {
        val config = mapOf(
            "level" to "DEBUG",
            "sinks" to listOf(mapOf("name" to "file", "file" to "logs/test.log", "levels" to "ALL"))
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null")
        assertEquals(LogLevel.DEBUG, result.minLevel)
    }

    @Test
    fun whenSinksConfigured_stdoutNotAdded() {
        val config = mapOf(
            "level" to "INFO",
            "sinks" to listOf(mapOf("name" to "file", "file" to "logs/only.log", "levels" to "ALL"))
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null")
        val hasStdout = result.rules.any { it.sinks.any { s -> s is SinkSpec.Stdout } }
        assertTrue(!hasStdout, "有 sinks 配置时不应自动附加 stdout")
    }

    @Test
    fun whenExplicitStdoutSink_respected() {
        val config = mapOf(
            "level" to "INFO",
            "sinks" to listOf(
                mapOf("name" to "stdout"),
                mapOf("name" to "file", "file" to "logs/all.log", "levels" to "ALL")
            )
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null")
        val hasStdout = result.rules.any { it.sinks.any { s -> s is SinkSpec.Stdout } }
        assertTrue(hasStdout, "显式 name=stdout 时应保留 stdout")
    }

    @Test
    fun whenAsyncConfigProvided_parsed() {
        val config = mapOf(
            "level" to "INFO",
            "async" to mapOf(
                "enabled" to true,
                "queueSize" to 4096,
                "flushEveryMs" to 100,
                "flushBatchSize" to 32,
                "shutdownFlushTimeoutMs" to 3000
            ),
            "sinks" to listOf(mapOf("name" to "file", "file" to "logs/test.log", "levels" to "ALL"))
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null")
        assertEquals(true, result.async.enabled)
        assertEquals(4096, result.async.queueSize)
        assertEquals(100, result.async.flushEveryMs)
        assertEquals(32, result.async.flushBatchSize)
        assertEquals(3000, result.async.shutdownFlushTimeoutMs)
    }

    @Test
    fun whenAsyncConfigAbsent_defaultsDisabled() {
        val config = mapOf(
            "level" to "INFO",
            "sinks" to listOf(mapOf("name" to "file", "file" to "logs/test.log", "levels" to "ALL"))
        )
        val result = parseLoggingConfig(config) ?: error("Expected non-null")
        assertEquals(false, result.async.enabled)
    }
}
