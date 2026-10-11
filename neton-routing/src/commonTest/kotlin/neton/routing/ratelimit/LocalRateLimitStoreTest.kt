package neton.routing.ratelimit

import kotlinx.coroutines.*
import kotlin.test.*

class LocalRateLimitStoreTest {
    @Test
    fun windowsRemainIndependentAndExpiredHeapIsDrained() = runBlocking {
        var now = 0L
        val store = LocalRateLimitStore { now }
        assertEquals(1L, store.incrementAndGet("same", 1).count)
        assertEquals(1L, store.incrementAndGet("same", 60).count)
        repeat(2000) { store.incrementAndGet("key$it", (it % 60) + 1) }
        now = 120
        repeat(2000) { assertEquals(1L, store.incrementAndGet("key$it", (it % 60) + 1).count) }
        assertEquals(1L, store.incrementAndGet("same", 1).count)
        assertFailsWith<IllegalArgumentException> { store.incrementAndGet("bad", 0) }
        Unit
    }

    @Test
    fun concurrentIncrementsAreNotLost() = runBlocking {
        val store = LocalRateLimitStore { 123L }
        coroutineScope {
            repeat(16) { launch(Dispatchers.Default) {
                repeat(100) { store.incrementAndGet("shared", 60) }
            } }
        }
        assertEquals(1601L, store.incrementAndGet("shared", 60).count)
    }
}
