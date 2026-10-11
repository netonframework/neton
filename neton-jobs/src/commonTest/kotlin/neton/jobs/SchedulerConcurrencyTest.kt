package neton.jobs

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import neton.core.component.NetonContext
import neton.jobs.internal.CoroutineJobScheduler
import neton.logging.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class SchedulerConcurrencyTest {
    @Test
    fun concurrentTriggersCannotOverlapOneJobAndSnapshotsAreCoherent() = runBlocking {
        val ctx = NetonContext(emptyArray())
        val logger = object : Logger {
            override fun trace(msg: String, fields: Fields) {}
            override fun debug(msg: String, fields: Fields) {}
            override fun info(msg: String, fields: Fields) {}
            override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
            override fun error(msg: String, fields: Fields, cause: Throwable?) {}
        }
        ctx.bind(LoggerFactory::class, object : LoggerFactory { override fun get(name: String) = logger })
        val active = MutableStateFlow(0)
        val overlap = MutableStateFlow(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = CoroutineJobScheduler(listOf(JobDefinition("test", JobSchedule.FixedRate(1000),
            ExecutionMode.ALL_NODES, 1000, false, {
                object : JobExecutor {
                    override suspend fun run(ctx: JobContext) {
                        active.update { it + 1 }
                        if (active.value != 1) overlap.value = true
                        entered.complete(Unit)
                        try { release.await() } finally { active.update { it - 1 } }
                    }
                }
            })), true, 1.seconds, ctx)
        try {
            scheduler.trigger("test")
            withTimeout(5000) { entered.await() }
            coroutineScope {
                repeat(256) { launch(Dispatchers.Default) {
                    scheduler.trigger("test")
                    val status = scheduler.snapshot().single()
                    assertEquals(status.runCount, status.failCount)
                } }
            }
            assertFalse(overlap.value)
            release.complete(Unit)
            withTimeout(5000) { while (scheduler.snapshot().single().runCount == 0L) yield() }
            assertFalse(overlap.value)
            val status = scheduler.snapshot().single()
            assertEquals(JobResult.SUCCESS, status.lastResult)
            assertNotNull(status.lastDuration)
        } finally { release.complete(Unit); scheduler.shutdown(1.seconds) }
        assertEquals(0, active.value)
    }
}
