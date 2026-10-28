package neton.jobs

import kotlinx.coroutines.*
import neton.core.component.NetonContext
import neton.jobs.internal.CoroutineJobScheduler
import neton.logging.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SchedulerShutdownTest {
    private fun scheduler(executor: JobExecutor): CoroutineJobScheduler {
        val ctx = NetonContext(emptyArray())
        val logger = object : Logger {
            override fun trace(msg: String, fields: Fields) {}
            override fun debug(msg: String, fields: Fields) {}
            override fun info(msg: String, fields: Fields) {}
            override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
            override fun error(msg: String, fields: Fields, cause: Throwable?) {}
        }
        ctx.bind(LoggerFactory::class, object : LoggerFactory { override fun get(name: String) = logger })
        return CoroutineJobScheduler(listOf(JobDefinition("test", JobSchedule.FixedRate(10),
            ExecutionMode.ALL_NODES, 1000, true, { executor })), true, 2.seconds, ctx)
    }

    @Test
    fun shutdownDrainsAnAcceptedExecutionBeforeCancelling() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val scheduler = scheduler(object : JobExecutor {
            override suspend fun run(ctx: JobContext) {
                entered.complete(Unit)
                release.await()
                completed.complete(Unit)
            }
        })
        scheduler.start()
        withTimeout(5000) { entered.await() }
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { scheduler.shutdown(2.seconds) }
        try {
            assertFalse(stopping.isCompleted)
            assertFailsWith<IllegalStateException> { scheduler.trigger("test") }
            assertFailsWith<IllegalStateException> { scheduler.start() }
            release.complete(Unit)
            withTimeout(5000) { stopping.await() }
            assertTrue(completed.isCompleted, "accepted work must finish before shutdown returns")
        } finally { release.complete(Unit); scheduler.shutdown(100.milliseconds) }
    }

    @Test
    fun deadlineCancelsWorkAndRepeatedShutdownIsSafe() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val scheduler = scheduler(object : JobExecutor {
            override suspend fun run(ctx: JobContext) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        })
        scheduler.trigger("test")
        withTimeout(5000) { entered.await() }
        withTimeout(5000) { scheduler.shutdown(50.milliseconds); cancelled.await() }
        scheduler.shutdown(50.milliseconds)
        assertFailsWith<IllegalStateException> { scheduler.trigger("test") }
        Unit
    }
}
