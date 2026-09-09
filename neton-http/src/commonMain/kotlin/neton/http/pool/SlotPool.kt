package neton.http.pool

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The lifecycle of one pooled request slot, and a bounded pool of them.
 *
 * Prototype foundation for reusing per-request objects. NOT yet wired into
 * dispatch — a slot returned while something still holds it would hand one
 * request's data to another, so the lifecycle is proven here first.
 *
 * Correctness model (v2, after review):
 * - **Lease identity.** Every lease gets a token; `close`/`retain`/`release` take
 *   that token and no-op if it is stale, so a late callback from a *previous*
 *   request cannot close, retain, or release the slot's *current* lease.
 * - **No check-then-act.** Each slot has its own lock; state, lease generation,
 *   consumer count and close flag are read and mutated inside it, never as two
 *   separate atomic steps.
 * - **Return exactly once**, only after every consumer releases; a create/reset
 *   that throws does not silently consume pool capacity or return a broken slot.
 * - **Allocation-free return.** The free list is an array-backed stack (no node
 *   per return). A plain lock, not a lock-free structure — correctness first;
 *   contention is a measurement, not an assumption.
 */

/** A non-reentrant test-and-set spinlock. No lambda, so acquiring it allocates nothing. */
@OptIn(ExperimentalAtomicApi::class)
internal class SpinLock {
    private val s = AtomicInt(0)
    fun lock() { while (!s.compareAndSet(0, 1)) { /* spin: critical sections are tiny */ } }
    fun unlock() { s.store(0) }
}

enum class SlotState { FREE, LEASED, CLOSING }

@OptIn(ExperimentalAtomicApi::class)
class RequestSlot<T> internal constructor(
    val value: T,
    private val reset: (T) -> Unit,
    private val onReturn: (RequestSlot<T>) -> Unit,
    private val onResetFailure: (RequestSlot<T>) -> Unit,
) {
    private val lock = SpinLock()
    private var stateField = SlotState.FREE
    private var leaseGen = 0L
    private var consumers = 0
    private var closeRequested = false

    val state: SlotState get() { lock.lock(); try { return stateField } finally { lock.unlock() } }

    /**
     * The current lease's token, or 0 if not leased. The caller captures this
     * right after leasing (before any suspension) and passes it to close/retain/
     * release so a callback from a previous lease is ignored.
     */
    val token: Long get() { lock.lock(); try { return if (stateField == SlotState.LEASED) leaseGen else 0L } finally { lock.unlock() } }

    /** Leases the slot and returns its token. Throws if not FREE (never leased twice). */
    internal fun lease(): Long {
        lock.lock()
        try {
            check(stateField == SlotState.FREE) { "slot leased while ${stateField}" }
            stateField = SlotState.LEASED
            leaseGen += 1
            consumers = 1            // the request itself
            closeRequested = false
            return leaseGen
        } finally { lock.unlock() }
    }

    /** Registers another consumer for [token]'s lease. False if the token is stale. */
    fun retain(token: Long): Boolean {
        lock.lock()
        try {
            if (stateField != SlotState.LEASED || leaseGen != token) return false
            consumers += 1
            return true
        } finally { lock.unlock() }
    }

    /** A consumer for [token] is done. Drains if it was the last and close was requested. */
    fun release(token: Long) {
        lock.lock()
        val drain: Boolean
        try {
            if (leaseGen != token || stateField != SlotState.LEASED) return
            consumers -= 1
            drain = consumers == 0 && closeRequested
            if (drain) stateField = SlotState.CLOSING
        } finally { lock.unlock() }
        if (drain) finishDrain()
    }

    /** Requests return of [token]'s lease. Idempotent and token-guarded. */
    fun close(token: Long) {
        lock.lock()
        val drain: Boolean
        try {
            if (leaseGen != token || stateField != SlotState.LEASED || closeRequested) return
            closeRequested = true
            consumers -= 1               // drop the request's own consumer count
            drain = consumers == 0
            if (drain) stateField = SlotState.CLOSING
        } finally { lock.unlock() }
        if (drain) finishDrain()
    }

    /** Reset happens outside the lock; CLOSING already blocks re-lease and stale ops. */
    private fun finishDrain() {
        try {
            reset(value)
        } catch (t: Throwable) {
            // A slot whose reset failed may still hold request data: never return
            // it to the pool. Drop it and let the pool reclaim its capacity.
            lock.lock(); try { leaseGen += 1 } finally { lock.unlock() }
            onResetFailure(this)
            return
        }
        lock.lock()
        try { stateField = SlotState.FREE; leaseGen += 1 } finally { lock.unlock() }
        onReturn(this)
    }

    /** True only if [token] is still the live lease — a use-after-return check. */
    fun validate(token: Long): Boolean {
        lock.lock()
        try { return stateField == SlotState.LEASED && leaseGen == token } finally { lock.unlock() }
    }
}

/**
 * Bounded, grow-on-demand pool. [lease] reuses a free slot or builds one up to
 * [maxSlots]; past the cap it returns null so the caller falls back to plain
 * allocation. The cap bounds *pooled* slots, not total in-flight requests — that
 * needs a separate admission limit.
 */
@OptIn(ExperimentalAtomicApi::class)
class SlotPool<T>(
    private val maxSlots: Int,
    private val create: () -> T,
    private val reset: (T) -> Unit,
) {
    private val lock = SpinLock()
    // Array-backed stack of free slots: push/pop are index ops, no allocation.
    private val freeStack = arrayOfNulls<RequestSlot<T>>(maxSlots)
    private var freeTop = 0        // number of free slots on the stack
    private var created = 0        // total slots the pool owns

    val createdCount: Int get() { lock.lock(); try { return created } finally { lock.unlock() } }
    val freeCount: Int get() { lock.lock(); try { return freeTop } finally { lock.unlock() } }

    fun lease(): RequestSlot<T>? {
        lock.lock()
        val reuse: RequestSlot<T>?
        val mayGrow: Boolean
        try {
            if (freeTop > 0) {
                reuse = freeStack[--freeTop]
                freeStack[freeTop] = null
                mayGrow = false
            } else {
                reuse = null
                mayGrow = created < maxSlots
                if (mayGrow) created += 1   // reserve capacity; released on create() failure
            }
        } finally { lock.unlock() }

        if (reuse != null) { reuse.lease(); return reuse }
        if (!mayGrow) return null
        val built = try {
            create()
        } catch (t: Throwable) {
            lock.lock(); try { created -= 1 } finally { lock.unlock() }  // don't consume capacity on failure
            throw t
        }
        val slot = RequestSlot(built, reset, ::push, ::onResetFailure)
        slot.lease()
        return slot
    }

    private fun push(slot: RequestSlot<T>) {
        lock.lock()
        try { if (freeTop < freeStack.size) freeStack[freeTop++] = slot }
        finally { lock.unlock() }
    }

    /** A slot whose reset threw is discarded; free its capacity for a replacement. */
    private fun onResetFailure(@Suppress("UNUSED_PARAMETER") slot: RequestSlot<T>) {
        lock.lock(); try { created -= 1 } finally { lock.unlock() }
    }
}
