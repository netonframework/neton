package neton.http.pool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lifecycle contract a request-object pool lives or dies by. Each test maps
 * to one of the acceptance rules: a slot is never leased twice, it returns
 * exactly once under a cancel/exception/normal race, it returns only after every
 * consumer is done, exhaustion is bounded, reset clears state, and a stale
 * reference cannot read the next request.
 */
class SlotPoolTest {

    private class Box(var data: String? = null)
    private fun pool(max: Int) = SlotPool(max, { Box() }, { it.data = null })

    @Test
    fun leaseReuseAndReset() {
        val p = pool(1)
        val a = p.lease()!!
        a.value.data = "req-1"
        a.close()
        // Same object comes back, cleared.
        val b = p.lease()!!
        assertTrue(a === b, "the one slot should be reused")
        assertNull(b.value.data, "reset must clear per-request data")
        assertEquals(1, p.createdCount, "no new allocation on reuse")
        b.close()
    }

    @Test
    fun exhaustionReturnsNullNotUnbounded() {
        val p = pool(2)
        val a = p.lease(); val b = p.lease()
        assertNotNull(a); assertNotNull(b)
        assertNull(p.lease(), "past the cap, lease returns null (caller falls back)")
        assertEquals(2, p.createdCount)
        a!!.close()
        assertNotNull(p.lease(), "a returned slot is available again")
    }

    @Test
    fun closeIsExactlyOnceUnderRacingPaths() {
        val p = pool(1)
        val s = p.lease()!!
        // Simulate cancel + exception + normal all calling close().
        s.close(); s.close(); s.close()
        // Slot returned once: exactly one free slot, reusable once.
        val again = p.lease()!!
        assertTrue(s === again)
        // A second lease must not be possible without a return in between.
        assertNull(p.lease())
    }

    @Test
    fun returnsOnlyAfterAllConsumersRelease() {
        val p = pool(1)
        val s = p.lease()!!
        s.retain() // a stream writer still in flight
        s.close()  // request finished, but the writer has not released
        assertNull(p.lease(), "slot must NOT return while a consumer is still active")
        s.release() // writer done → now it drains
        assertNotNull(p.lease(), "slot returns once the last consumer releases")
    }

    @Test
    fun staleTokenIsRefusedAfterReturn() {
        val p = pool(1)
        val s = p.lease()!!
        val token = s.generation.toLong()
        assertTrue(s.validate(token), "token valid while leased")
        s.close()
        assertFalse(s.validate(token), "a token from the previous lease must not validate")
        val reused = p.lease()!!
        assertFalse(reused.validate(token), "the old token must not read the new lease")
    }

    @Test
    fun growsOnDemandNotUpfront() {
        val p = pool(100)
        assertEquals(0, p.createdCount, "nothing allocated before first use")
        val s1 = p.lease()!!; assertEquals(1, p.createdCount)
        val s2 = p.lease()!!; assertEquals(2, p.createdCount)
        s1.close(); s2.close()
        // Reuse does not grow the pool.
        p.lease()!!.close()
        assertEquals(2, p.createdCount, "grows to the high-water mark, then reuses")
    }
}
