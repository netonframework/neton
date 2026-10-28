package neton.ai.protocol.convert

import neton.ai.protocol.ir.ReasoningMode
import neton.ai.protocol.ir.ReasoningOptions

/**
 * 模型名推理尾缀（spec/ai.md §5.4，主流中转站的约定）：
 *
 * | 尾缀 | 含义 |
 * |---|---|
 * | `-thinking` | 开启推理（自适应） |
 * | `-thinking-<预算>` | 开启推理，预算 token 数 |
 * | `-nothinking` | 关闭推理 |
 * | `-minimal` / `-low` / `-medium` / `-high` / `-xhigh` | 推理强度 |
 *
 * 是否启用由应用决定；模型名本身以这些词结尾时（如某些厂商的 `*-high` 正式型号），应用应先查自己的模型表再决定是否解析。
 */
@neton.ai.ExperimentalNetonAiApi
object ReasoningSuffix {
    data class Parsed(val model: String, val reasoning: ReasoningOptions?)

    private val BUDGET = Regex("^(.+)-thinking-(\\d+)$")
    private val EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh")

    fun parse(model: String): Parsed {
        BUDGET.matchEntire(model)?.let { m ->
            return Parsed(m.groupValues[1], ReasoningOptions(mode = ReasoningMode.Enabled, budgetTokens = m.groupValues[2].toInt()))
        }
        if (model.endsWith("-nothinking")) return Parsed(model.removeSuffix("-nothinking"), ReasoningOptions(mode = ReasoningMode.Disabled))
        if (model.endsWith("-thinking")) return Parsed(model.removeSuffix("-thinking"), ReasoningOptions(mode = ReasoningMode.Adaptive))
        for (e in EFFORTS) if (model.endsWith("-$e") && model.length > e.length + 1) {
            return Parsed(model.removeSuffix("-$e"), ReasoningOptions(effort = e))
        }
        return Parsed(model, null)
    }
}
