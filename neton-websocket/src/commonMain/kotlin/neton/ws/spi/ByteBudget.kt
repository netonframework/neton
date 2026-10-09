package neton.ws.spi

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Nonblocking, thread-safe accounting. The implementation never waits for another connection. */
interface ByteBudget {
    fun tryReserve(bytes: Long): Boolean
    fun release(bytes: Long)
}

object UnlimitedByteBudget : ByteBudget {
    override fun tryReserve(bytes: Long) = true
    override fun release(bytes: Long) = Unit
}

@OptIn(ExperimentalAtomicApi::class)
class BudgetLease(private val budget: ByteBudget, val bytes: Long) {
    private val released = AtomicBoolean(false)
    fun release() { if (released.compareAndSet(false, true)) budget.release(bytes) }
}

class WebSocketCapacityException : IllegalStateException("WebSocket resource pressure")
