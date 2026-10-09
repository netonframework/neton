package neton.ws

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import neton.ws.spi.ByteBudget
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal class RuntimeBudget(val limit: Long) : ByteBudget {
    private val used = AtomicLong(0)
    val changed = MutableStateFlow(0L)
    val usage get() = used.load()
    override fun tryReserve(bytes: Long): Boolean {
        require(bytes >= 0)
        while (true) {
            val current = used.load()
            if (bytes > limit - current) return false
            if (used.compareAndSet(current, current + bytes)) return true
        }
    }
    override fun release(bytes: Long) {
        require(bytes >= 0)
        check(used.fetchAndAdd(-bytes) >= bytes) { "Budget released twice" }
        changed.update { it + 1 }
    }
}

internal class CombinedBudget(private val global: RuntimeBudget, private val local: RuntimeBudget) : ByteBudget {
    override fun tryReserve(bytes: Long): Boolean {
        if (!local.tryReserve(bytes)) return false
        if (global.tryReserve(bytes)) return true
        local.release(bytes)
        return false
    }
    override fun release(bytes: Long) { local.release(bytes); global.release(bytes) }
}

/** Exact UTF-8 length without allocating an encoded array; overflow-safe for admission. */
internal fun utf8Bytes(value: String): Long {
    var size = 0L
    var i = 0
    while (i < value.length) {
        val c = value[i++] .code
        size += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c in 0xd800..0xdbff && i < value.length && value[i].code in 0xdc00..0xdfff -> { i++; 4 }
            else -> 3
        }
    }
    return size
}
