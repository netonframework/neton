package neton.ai.protocol.stream

import neton.ai.protocol.ir.Block
import neton.ai.protocol.ir.Delta
import neton.ai.protocol.ir.ProviderMetadata
import neton.ai.protocol.ir.StreamEvent

/**
 * 流式解码器共用的块开闭器（spec §6）。[key] 标识上游的一个块（如「文本」「第 3 个工具调用」）。
 *
 * 上游可能交错发送多个块（Anthropic 的多个 content block、OpenAI 并行工具调用的参数），所以 IR 允许
 * 同时有多个打开的块，按 index 寻址；需要连续输出的目标协议由 [SequentialBlocks] 重新排好。
 * `exclusive = true`（文本、推理这类天然先后出现的块）在打开前关掉其他所有打开的块。
 */
class BlockSequencer {
    private var nextIndex = 0
    private val open = LinkedHashMap<Any, Int>()

    val isOpen: Boolean get() = open.isNotEmpty()
    fun isOpen(key: Any): Boolean = key in open
    fun indexOf(key: Any): Int? = open[key]

    /** 最近打开、仍未关闭的块。 */
    val openIndex: Int? get() = open.values.lastOrNull()

    fun ensure(key: Any, out: MutableList<StreamEvent>, exclusive: Boolean = true, initial: () -> Block): Int {
        open[key]?.let { return it }
        if (exclusive) close(out)
        val index = nextIndex++
        open[key] = index
        out += StreamEvent.BlockStart(index, initial())
        return index
    }

    fun delta(key: Any, delta: Delta, out: MutableList<StreamEvent>, exclusive: Boolean = true, initial: () -> Block) {
        val index = ensure(key, out, exclusive, initial)
        out += StreamEvent.BlockDelta(index, delta)
    }

    fun close(key: Any, out: MutableList<StreamEvent>, metadata: ProviderMetadata = ProviderMetadata.EMPTY) {
        val index = open.remove(key) ?: return
        out += StreamEvent.BlockStop(index, metadata)
    }

    /** 关闭所有打开的块（按打开顺序）。 */
    fun close(out: MutableList<StreamEvent>, metadata: ProviderMetadata = ProviderMetadata.EMPTY) {
        for (key in open.keys.toList()) close(key, out, metadata)
    }
}

/**
 * 把可能交错的块事件排成「一个块完整输出（start → delta… → stop）后再输出下一个」，
 * 供要求块连续的目标协议（Anthropic、Responses）的编码器使用。非块事件直接放行；
 * Finish / Error 之前先把排队的块全部交出。
 */
class SequentialBlocks {
    private var current: Int? = null
    private val queued = LinkedHashMap<Int, MutableList<StreamEvent>>()

    fun accept(e: StreamEvent): List<StreamEvent> = when (e) {
        is StreamEvent.BlockStart -> if (current == null) { current = e.index; listOf(e) } else { queued[e.index] = mutableListOf(e); emptyList() }
        is StreamEvent.BlockDelta -> if (e.index == current) listOf(e) else { queued.getOrPut(e.index) { mutableListOf() } += e; emptyList() }
        is StreamEvent.BlockStop -> if (e.index == current) { current = null; listOf<StreamEvent>(e) + drain() } else {
            queued.getOrPut(e.index) { mutableListOf() } += e; emptyList()
        }
        is StreamEvent.Finish, is StreamEvent.Error -> flush() + e
        else -> listOf(e)
    }

    /** 当前块结束后，依次交出排队的块；遇到尚未结束的块就把它设为当前块并停下。 */
    private fun drain(): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        while (current == null && queued.isNotEmpty()) {
            val (index, events) = queued.entries.first()
            queued.remove(index)
            out += events
            if (events.none { it is StreamEvent.BlockStop }) current = index
        }
        return out
    }

    /** 交出全部排队事件（流结束时调用）；未结束的块留给编码器在收尾时关闭。 */
    fun flush(): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        for ((_, events) in queued) out += events
        queued.clear()
        current = null
        return out
    }
}
