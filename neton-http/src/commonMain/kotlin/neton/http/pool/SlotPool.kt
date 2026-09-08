package neton.http.pool

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The lifecycle of one pooled request slot, and a bounded pool of them.
 *
 * This is the foundation for reusing per-request objects (context/request/
 * response skeletons): a request leases a slot, fills it, runs, and — only once
 * every consumer that could still touch it is done — the slot is reset and
 * returned for the next request. Grow-on-demand up to a cap, then pure reuse:
 * the pool climbs to the concurrency high-water mark and after that allocates
 * nothing, matching the "grow, then just reset" model.
 *
 * This file is the lifecycle + container only; it is not yet wired into dispatch.
 * Correctness here is what makes wiring safe, because a slot returned while
 * something still reads it would hand one request's data to another.
 */

/** A slot's lifecycle. FREE in the pool, LEASED while serving, CLOSING while draining. */
enum class SlotState { FREE, LEASED, CLOSING }

/**
 * A reusable slot wrapping a [value] built once. [reset] runs before the slot
 * goes back to FREE and must clear every per-request reference on [value].
 *
 * Safety this type guarantees:
 * - **Return exactly once.** [close] on a leased slot is a CAS LEASED→CLOSING;
 *   only the winner drains and returns it, so a cancel / exception / normal-
 *   completion race returns the slot once, never twice.
 * - **Return only after consumers finish.** Each thing that may still touch the
 *   slot [retain]s it and [release]s when done; the actual reset+return happens
 *   when the last consumer releases *and* close has been requested.
 * - **Stale-reference detection.** [generation] bumps on every lease, so a
 *   reference kept past return can be checked ([validate]) and refused rather
 *   than silently reading the next request's data.
 */
@OptIn(ExperimentalAtomicApi::class)
class RequestSlot<T> internal constructor(
    val value: T,
    private val reset: (T) -> Unit,
    private val onReturn: (RequestSlot<T>) -> Unit,
) {
    private val stateRef = AtomicReference(SlotState.FREE)
    private val consumers = AtomicInt(0)
    private val closeRequested = AtomicBoolean(false)
    private val gen = AtomicInt(0)

    val state: SlotState get() = stateRef.load()
    val generation: Int get() = gen.load()

    /** Marks the slot LEASED. Fails if it is not FREE (never leased twice). */
    internal fun lease(): Long {
        check(stateRef.compareAndSet(SlotState.FREE, SlotState.LEASED)) {
            "slot leased while not FREE (state=${stateRef.load()})"
        }
        closeRequested.store(false)
        consumers.store(1) // the request itself is the first consumer
        return gen.incrementAndFetch().toLong()
    }

    /** Registers another consumer (a stream writer, an async task) that may touch the slot. */
    fun retain() {
        check(stateRef.load() == SlotState.LEASED) { "retain on a non-leased slot" }
        consumers.incrementAndFetch()
    }

    /** A consumer is done. When the last one releases and close was requested, drain. */
    fun release() {
        val left = consumers.decrementAndFetch()
        check(left >= 0) { "release without a matching retain/lease" }
        if (left == 0 && closeRequested.load()) drain()
    }

    /**
     * Requests return of the slot. Idempotent: the first caller (of possibly
     * racing cancel/exception/normal paths) wins the LEASED→CLOSING transition.
     * The slot only actually returns once every consumer has released.
     */
    fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // The lease counted as one consumer; drop it. If others are still in
        // flight, the last release() drains; otherwise we drain now.
        release()
    }

    private fun drain() {
        if (!stateRef.compareAndSet(SlotState.LEASED, SlotState.CLOSING)) return
        gen.incrementAndFetch() // invalidate any reference held past this point
        reset(value)
        stateRef.store(SlotState.FREE)
        onReturn(this)
    }

    /** True only if [token] still matches the current lease — a use-after-return check. */
    fun validate(token: Long): Boolean = stateRef.load() == SlotState.LEASED && gen.load().toLong() == token
}

/**
 * Bounded, grow-on-demand pool. [lease] reuses a free slot, or builds a new one
 * up to [maxSlots]; past the cap it returns null so the caller can fall back to
 * an unpooled path rather than block or grow without limit.
 */
@OptIn(ExperimentalAtomicApi::class)
class SlotPool<T>(
    private val maxSlots: Int,
    private val create: () -> T,
    private val reset: (T) -> Unit,
) {
    // A Treiber stack of free slots: lease/return are lock-free pushes/pops, so
    // returns from whatever thread a coroutine resumed on do not contend a lock.
    private val free = AtomicReference<Node<T>?>(null)
    private val created = AtomicInt(0)
    val createdCount: Int get() = created.load()

    private class Node<T>(val slot: RequestSlot<T>, val next: Node<T>?)

    fun lease(): RequestSlot<T>? {
        while (true) {
            val head = free.load()
            if (head != null) {
                if (free.compareAndSet(head, head.next)) {
                    head.slot.lease()
                    return head.slot
                }
                continue // lost the race, retry
            }
            // Empty: grow if under the cap.
            val n = created.load()
            if (n >= maxSlots) return null
            if (created.compareAndSet(n, n + 1)) {
                val slot = RequestSlot(create(), reset, ::push)
                slot.lease()
                return slot
            }
        }
    }

    private fun push(slot: RequestSlot<T>) {
        while (true) {
            val head = free.load()
            if (free.compareAndSet(head, Node(slot, head))) return
        }
    }
}
