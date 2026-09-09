package neton.http.pool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlotPoolTest {

    private class Box(var data: String? = null)
    private fun pool(max: Int) = SlotPool(max, { Box() }, { it.data = null })

    @Test
    fun reuseAndResetOnClose() {
        val p = pool(1)
        val a = p.lease()!!
        a.value.data = "req-1"
        a.close(a.token)
        val b = p.lease()!!
        assertTrue(a === b, "the one slot is reused")
        assertNull(b.value.data, "reset cleared per-request data")
        assertEquals(1, p.createdCount)
        b.close(b.token)
    }

    @Test
    fun exhaustionReturnsNull() {
        val p = pool(2)
        val a = p.lease(); val b = p.lease()
        assertNotNull(a); assertNotNull(b)
        assertNull(p.lease(), "past cap → null")
        a!!.close(a.token)
        assertNotNull(p.lease(), "returned slot reusable")
    }

    @Test
    fun aLateCloseFromTheOldLeaseCannotCloseTheNewOne() {
        val p = pool(1)
        val a = p.lease()!!
        val tokenA = a.token
        a.close(tokenA)                 // A finishes; slot returns
        val b = p.lease()!!             // same slot, new lease
        assertTrue(a === b)
        b.value.data = "req-B"
        a.close(tokenA)                 // A's LATE callback with the OLD token
        assertTrue(b.validate(b.token), "B's lease must survive a stale close")
        assertEquals("req-B", b.value.data, "a stale close must not reset the new request")
    }

    @Test
    fun staleRetainAndReleaseAreNoOps() {
        val p = pool(1)
        val a = p.lease()!!
        val old = a.token
        a.close(old)
        val b = p.lease()!!
        val bt = b.token
        assertFalse(b.retain(old), "retain with a stale token must fail")
        b.release(old)                  // stale release ignored
        b.close(bt)
        assertEquals(1, p.freeCount, "exactly one free slot after a correct close")
    }

    @Test
    fun returnsOnlyAfterAllConsumersRelease() {
        val p = pool(1)
        val a = p.lease()!!
        val t = a.token
        assertTrue(a.retain(t))         // a stream writer
        a.close(t)                      // request done, writer active
        assertNull(p.lease(), "must not return while a consumer is active")
        a.release(t)                    // writer done
        assertNotNull(p.lease(), "returns after the last consumer releases")
    }

    @Test
    fun createFailureDoesNotConsumeCapacity() {
        var fail = true
        val p = SlotPool<Box>(1, { if (fail) throw RuntimeException("boom") else Box() }, { it.data = null })
        try { p.lease(); error("should have thrown") } catch (_: RuntimeException) {}
        assertEquals(0, p.createdCount, "a failed create must not consume the cap")
        fail = false
        assertNotNull(p.lease(), "capacity still available after the failure")
    }

    @Test
    fun resetFailureDropsTheSlotAndFreesCapacity() {
        var failReset = false
        val p = SlotPool<Box>(1, { Box() }, { if (failReset) throw RuntimeException("reset boom") else it.data = null })
        val a = p.lease()!!
        assertEquals(1, p.createdCount)
        failReset = true
        a.close(a.token)                // reset throws → slot dropped
        assertEquals(0, p.freeCount, "a slot whose reset threw is not returned")
        assertEquals(0, p.createdCount, "its capacity is freed for a replacement")
        failReset = false
        assertNotNull(p.lease(), "a fresh slot can be created after the drop")
    }

    /**
     * Real concurrency: many coroutines on a multi-thread dispatcher hammer
     * lease→(maybe retain/release)→close. Invariants must hold throughout —
     * never over the cap, never a double-return (freeCount <= createdCount), and
     * every slot ends returnable.
     */
    @Test
    fun concurrentLeaseCloseKeepsInvariants() = runBlocking {
        val max = 16
        val p = pool(max)
        withContext(Dispatchers.Default) {
            (1..64).map {
                async {
                    repeat(500) {
                        val s = p.lease()
                        if (s != null) {
                            val t = s.token
                            s.value.data = "x"
                            if (it % 3 == 0 && s.retain(t)) s.release(t)
                            s.close(t)
                            // A stale double-close must be harmless.
                            s.close(t)
                        }
                    }
                }
            }.awaitAll()
        }
        assertTrue(p.createdCount <= max, "never exceeded the cap: ${p.createdCount}")
        assertTrue(p.freeCount <= p.createdCount, "no double-return: free=${p.freeCount} created=${p.createdCount}")
        // Everything is idle and returnable now.
        assertEquals(p.createdCount, p.freeCount, "all slots returned after the storm")
    }
}
