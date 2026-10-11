package neton.logging

import kotlinx.coroutines.*
import kotlin.test.*

class CoroutineLogContextTest {
    @Test
    fun interleavedRequestsKeepTheirOwnContextAcrossDispatcherChanges() = runBlocking {
        val records = mutableListOf<Fields>()
        val logger = object : Logger {
            override fun trace(msg: String, fields: Fields) {}
            override fun debug(msg: String, fields: Fields) {}
            override fun info(msg: String, fields: Fields) { records += fields }
            override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
            override fun error(msg: String, fields: Fields, cause: Throwable?) {}
        }
        val firstBound = CompletableDeferred<Unit>()
        val secondBound = CompletableDeferred<Unit>()
        val first = launch(LogContext("first")) {
            val bound = logger.withCurrentContext()
            firstBound.complete(Unit)
            secondBound.await()
            withContext(Dispatchers.Default) {
                assertEquals("first", currentCoroutineContext()[LogContext]?.traceId)
            }
            bound.info("first")
        }
        val second = launch(LogContext("second")) {
            firstBound.await()
            val bound = logger.withCurrentContext()
            secondBound.complete(Unit)
            yield()
            bound.info("second")
        }
        joinAll(first, second)
        assertEquals(setOf("first", "second"), records.map { it["traceId"] }.toSet())
        logger.withContext(LogContext("old")).withContext(LogContext("new")).info("rebound")
        assertEquals("new", records.last()["traceId"])
        assertNull(currentCoroutineContext()[LogContext])
        assertSame(logger, logger.withCurrentContext())
    }
}
