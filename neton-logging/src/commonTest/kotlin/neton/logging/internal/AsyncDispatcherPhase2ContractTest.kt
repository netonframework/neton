package neton.logging.internal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import neton.logging.LogLevel
import neton.logging.emptyFields
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 2 Async Dispatcher 行为契约测试（11B.7）。
 * 需在 Native target 运行（createTestRoutingLoggerFactory 依赖 createAsyncLogDispatcher 的 Native actual）。
 */
class AsyncDispatcherPhase2ContractTest {

    @Test
    fun debugInfo_droppedWhenQueueFull_andLogDroppedAppears() = runBlocking {
        val collect = CollectingSink()
        val config = LoggingConfig(
            rules = listOf(
                RouteRule("all", LogLevel.entries.toSet(), msgEquals = null, sinks = listOf(SinkSpec.File("test")))
            ),
            minLevel = LogLevel.DEBUG,
            async = LoggingAsyncConfig(enabled = true, queueSize = 1, droppedWarnIntervalSec = 1L)
        )
        val sinks = mapOf("file:test" to collect)
        val factory = createTestRoutingLoggerFactory(config, sinks, queueCapacity = 1, droppedWarnIntervalSec = 1L)
        val logger = factory.get("test")

        repeat(100) { logger.debug("flood-$it", emptyFields()) }
        delay(2500) // 等待 1s 窗口触发 log.dropped

        val hasDropped = collect.lines.any { it.contains("\"msg\":\"log.dropped\"") }
        assertTrue(hasDropped, "队列满时应有 log.dropped 输出")
        (factory as RoutingLoggerFactory).close()
    }

    @Test
    fun warnError_notDroppedWhenQueueFull() = runBlocking {
        val collect = CollectingSink()
        val config = LoggingConfig(
            rules = listOf(
                RouteRule("all", LogLevel.entries.toSet(), msgEquals = null, sinks = listOf(SinkSpec.File("test")))
            ),
            minLevel = LogLevel.DEBUG,
            async = LoggingAsyncConfig(enabled = true, queueSize = 1)
        )
        val sinks = mapOf("file:test" to collect)
        val factory = createTestRoutingLoggerFactory(config, sinks, queueCapacity = 1)
        val logger = factory.get("test")

        repeat(50) { logger.error("err-$it", emptyFields()) }
        (factory as RoutingLoggerFactory).close()
        delay(500) // 等待 writer drain

        val errorLines = collect.lines.count { it.contains("\"level\":\"ERROR\"") }
        assertTrue(errorLines >= 49, "ERROR 不应丢，至少应有 49 条（queueSize=1 时 fallback）")
    }
}
