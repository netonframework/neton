package neton.ai.protocol.stream

import kotlinx.serialization.json.JsonPrimitive
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.ir.Delta
import neton.ai.protocol.ir.StreamEvent
import neton.ai.protocol.ir.ToolCallBlock
import neton.ai.protocol.ir.ToolKind

/**
 * 把 custom 工具调用（输入是原始文本）改写成函数调用 `{"input": "<文本>"}`，
 * 供没有 custom 工具的目标协议（Anthropic、Gemini、Bedrock）的流式编码器使用，写法与非流式转换一致。
 * 文本逐段转义后直接输出，不需要缓冲；块结束时补上收尾的 `"}`。
 */
class CustomToolCallsAsFunctions(private val d: Diagnostics) {
    private val open = HashSet<Int>()

    fun accept(e: StreamEvent): List<StreamEvent> {
        if (e is StreamEvent.BlockStart) {
            val b = e.block as? ToolCallBlock
            if (b?.kind == ToolKind.Custom) {
                open += e.index
                d.warn(DiagnosticCodes.CUSTOM_TOOL_AS_FUNCTION, "content[${e.index}]", "custom tool call sent as a function call with {input}")
                return listOf(
                    StreamEvent.BlockStart(e.index, b.copy(kind = ToolKind.Function, arguments = "")),
                    StreamEvent.BlockDelta(e.index, Delta.Arguments("{\"input\":\"" + escape(b.arguments))),
                )
            }
        }
        if (e is StreamEvent.BlockDelta && e.index in open) {
            val dl = e.delta as? Delta.Arguments ?: return listOf(e)
            return listOf(StreamEvent.BlockDelta(e.index, Delta.Arguments(escape(dl.json))))
        }
        if (e is StreamEvent.BlockStop && open.remove(e.index)) return listOf(StreamEvent.BlockDelta(e.index, Delta.Arguments("\"}")), e)
        return listOf(e)
    }

    private fun escape(s: String): String = JsonPrimitive(s).toString().let { it.substring(1, it.length - 1) }
}
