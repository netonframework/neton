package neton.ai.protocol.stream

import neton.ai.protocol.ir.Block
import neton.ai.protocol.ir.Delta
import neton.ai.protocol.ir.ProviderMetadata
import neton.ai.protocol.ir.StreamEvent

/**
 * 流式解码器共用的块开闭器（spec §6：按块顺序开闭，先关后开）。
 * [key] 标识上游的一个块（如「文本」「推理」「第 3 个工具调用」）：key 变了就关掉当前块、开新块。
 */
class BlockSequencer {
    private var nextIndex = 0
    private var openKey: Any? = null
    var openIndex: Int? = null
        private set

    val isOpen: Boolean get() = openIndex != null
    fun isOpen(key: Any): Boolean = openIndex != null && openKey == key

    /** 确保 [key] 对应的块处于打开状态，返回其 index。 */
    fun ensure(key: Any, out: MutableList<StreamEvent>, initial: () -> Block): Int {
        openIndex?.let { if (openKey == key) return it }
        close(out)
        val index = nextIndex++
        openKey = key
        openIndex = index
        out += StreamEvent.BlockStart(index, initial())
        return index
    }

    fun delta(key: Any, delta: Delta, out: MutableList<StreamEvent>, initial: () -> Block) {
        val index = ensure(key, out, initial)
        out += StreamEvent.BlockDelta(index, delta)
    }

    fun close(out: MutableList<StreamEvent>, metadata: ProviderMetadata = ProviderMetadata.EMPTY) {
        val index = openIndex ?: return
        out += StreamEvent.BlockStop(index, metadata)
        openIndex = null
        openKey = null
    }
}
