package neton.ai.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import neton.ai.*
import neton.ai.protocol.error.ErrorKind
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.ir.*
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness

/** SDK 类型 ↔ 协议层 IR（spec §10：SDK 建立在协议层 IR 之上）。 */
internal object ModelMapping {

    fun toChatRequest(
        model: String,
        instructions: String?,
        messages: List<AiMessage>,
        tools: List<AiTool>,
        toolChoice: neton.ai.ToolChoice,
        settings: AiCallSettings,
        responseFormat: ResponseFormat?,
        providerOptions: Map<String, JsonObject>,
        stream: Boolean,
    ): ChatRequest {
        val ins = mutableListOf<Instruction>()
        instructions?.takeIf { it.isNotBlank() }?.let { ins += Instruction(it) }
        val out = mutableListOf<Message>()
        for (m in messages) {
            when (m.role) {
                AiRole.System -> if (out.isEmpty()) ins += Instruction(m.text) else out += Message(Role.System, listOf(TextBlock(m.text)))
                AiRole.User -> out += Message(Role.User, m.content.mapNotNull(::toBlock))
                AiRole.Assistant -> out += Message(Role.Assistant, m.content.mapNotNull(::toBlock))
                AiRole.Tool -> {
                    val results = m.content.filterIsInstance<AiContent.ToolResult>().filter { !it.preliminary }.map(::toResultBlock)
                    if (results.isNotEmpty()) out += Message(Role.Tool, results)
                }
            }
        }
        return ChatRequest(
            model = model,
            instructions = ins,
            messages = out,
            tools = tools.map(::toToolSpec),
            options = CallOptions(
                maxOutputTokens = settings.maxOutputTokens,
                temperature = settings.temperature,
                topP = settings.topP,
                topK = settings.topK,
                stop = settings.stopSequences,
                seed = settings.seed,
                presencePenalty = settings.presencePenalty,
                frequencyPenalty = settings.frequencyPenalty,
                toolChoice = if (tools.isEmpty()) null else toIrChoice(toolChoice),
                responseFormat = responseFormat,
                reasoning = toReasoning(settings),
                providerOptions = providerOptions,
            ),
            stream = stream,
        )
    }

    fun toReasoning(s: AiCallSettings): ReasoningOptions? {
        s.reasoningBudgetTokens?.let { return ReasoningOptions(mode = ReasoningMode.Enabled, budgetTokens = it, effort = s.reasoning?.effort) }
        return when (val r = s.reasoning) {
            null, AiReasoning.ProviderDefault -> null
            AiReasoning.None -> ReasoningOptions(mode = ReasoningMode.Disabled, effort = "none")
            else -> ReasoningOptions(effort = r.effort)
        }
    }

    private fun toIrChoice(c: neton.ai.ToolChoice): neton.ai.protocol.ir.ToolChoice = when (c) {
        neton.ai.ToolChoice.Auto -> neton.ai.protocol.ir.ToolChoice.Auto
        neton.ai.ToolChoice.None -> neton.ai.protocol.ir.ToolChoice.None
        neton.ai.ToolChoice.Required -> neton.ai.protocol.ir.ToolChoice.Required
        is neton.ai.ToolChoice.Named -> neton.ai.protocol.ir.ToolChoice.Named(c.name)
    }

    private fun toToolSpec(t: AiTool): ToolSpec = when (val k = t.kind) {
        is AiToolKind.Provider -> providerTool(t.name, k)
        else -> FunctionTool(t.name, t.description, t.inputSchema, t.strict)
    }

    /** 厂商托管工具：`厂商.类型` → 该厂商协议的原生工具定义。 */
    private fun providerTool(name: String, k: AiToolKind.Provider): ToolSpec {
        val vendor = k.id.substringBefore('.')
        val type = k.id.substringAfter('.')
        return when (vendor) {
            "anthropic" -> HostedTool(type, JsonObject(mapOf("type" to JsonPrimitive(type), "name" to JsonPrimitive(name)) + k.args), WireFormat.ANTHROPIC)
            "openai" -> HostedTool(type, JsonObject(mapOf("type" to JsonPrimitive(type)) + k.args), WireFormat.OPENAI_RESPONSES)
            "google" -> HostedTool(type, JsonObject(mapOf(camel(type) to k.args)), WireFormat.GEMINI)
            "bedrock" -> HostedTool(type, JsonObject(mapOf(type to k.args)), WireFormat.BEDROCK_CONVERSE)
            else -> HostedTool(type, JsonObject(mapOf("type" to JsonPrimitive(type)) + k.args), WireFormat.OPENAI_CHAT)
        }
    }

    private fun camel(s: String) = s.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun toBlock(c: AiContent): Block? = when (c) {
        is AiContent.Text -> TextBlock(c.text, c.citations, c.metadata)
        is AiContent.Image -> ImageBlock(c.source, c.detail, c.metadata)
        is AiContent.File -> FileBlock(c.source, c.filename, c.metadata)
        is AiContent.Reasoning -> ReasoningBlock(c.text, c.signature, c.redactedData, c.encryptedContent, metadata = c.metadata)
        is AiContent.Refusal -> RefusalBlock(c.text, c.metadata)
        // 修复失败的调用：参数不是对象时按 {} 发回，结果里带着错误说明，模型据此改正
        is AiContent.ToolCall -> ToolCallBlock(c.call.id, c.call.name,
            if (c.invalid && c.call.input !is JsonObject) "{}" else c.call.argumentsJson.ifBlank { "{}" }, metadata = c.metadata)
        is AiContent.Opaque -> c.block
        is AiContent.ToolResult, is AiContent.ToolApprovalRequest, is AiContent.ToolApprovalResponse, is AiContent.Source -> null
    }

    private fun toResultBlock(r: AiContent.ToolResult): ToolResultBlock {
        val content: List<Block> = when (val o = r.output) {
            is AiToolOutput.Content -> o.parts.mapNotNull(::toBlock)
            else -> listOf(TextBlock(o.asText()))
        }
        return ToolResultBlock(r.toolCallId, content, isError = r.output.isError, toolName = r.toolName, metadata = r.metadata)
    }

    // ───────────────────────── IR → SDK ─────────────────────────

    fun toContent(b: Block): List<AiContent> = when (b) {
        is TextBlock -> listOf(AiContent.Text(b.text, b.citations, b.metadata))
        is ImageBlock -> listOf(AiContent.Image(b.source, b.detail, b.metadata))
        is FileBlock -> listOf(AiContent.File(b.source, b.filename, b.metadata))
        is ReasoningBlock -> listOf(AiContent.Reasoning(b.text.ifEmpty { b.summary.joinToString("\n") }, b.signature, b.redactedData, b.encryptedContent, b.metadata))
        is RefusalBlock -> listOf(AiContent.Refusal(b.text, b.metadata))
        is ToolCallBlock -> listOf(AiContent.ToolCall(AiToolCall(b.id, b.name, b.arguments.ifBlank { "{}" }), b.metadata))
        is HostedToolBlock, is RawBlock -> listOf(AiContent.Opaque(b))
        is ToolResultBlock -> emptyList()
    }

    /** 文本块里的引用 → 来源（URL 与标题；各协议的引用形状不同，取共有的字段）。 */
    fun sourcesOf(content: List<AiContent>, startIndex: Int): List<AiContent.Source> {
        var n = startIndex
        return content.filterIsInstance<AiContent.Text>().flatMap { t ->
            t.citations.mapNotNull { c ->
                val url = (c["url"] as? JsonPrimitive)?.contentOrNull ?: ((c["url_citation"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
                val title = (c["title"] as? JsonPrimitive)?.contentOrNull ?: (c["document_title"] as? JsonPrimitive)?.contentOrNull
                if (url == null && title == null) null else AiContent.Source("src_${n++}", url, title)
            }
        }.distinctBy { it.url ?: it.title }
    }

    fun toFinish(r: FinishReason?, hasToolCalls: Boolean): AiFinishReason = when (r) {
        FinishReason.Stop -> if (hasToolCalls) AiFinishReason.ToolCalls else AiFinishReason.Stop
        FinishReason.Length -> AiFinishReason.Length
        FinishReason.ToolCalls -> AiFinishReason.ToolCalls
        FinishReason.ContentFilter, FinishReason.Refusal -> AiFinishReason.ContentFilter
        FinishReason.Error -> AiFinishReason.Error
        // pause_turn（托管工具长任务暂停）与未知原因：有工具调用时按工具调用处理
        FinishReason.PauseTurn, FinishReason.Other, null -> if (hasToolCalls) AiFinishReason.ToolCalls else AiFinishReason.Other
    }

    fun toUsage(u: Usage?): AiUsage {
        if (u == null || !u.hasAnyCount) return AiUsage.UNKNOWN
        val read = u.cacheReadTokens
        val write = u.cacheWriteTokens
        return AiUsage(
            inputTokens = u.inputTokens,
            inputTokenDetails = AiUsage.InputDetails(
                noCacheTokens = u.inputTokens?.let { it - (read ?: 0) - (write ?: 0) },
                cacheReadTokens = read,
                cacheWriteTokens = write,
            ),
            outputTokens = u.outputTokens,
            outputTokenDetails = AiUsage.OutputDetails(
                textTokens = u.outputTokens?.let { o -> u.reasoningTokens?.let { o - it } ?: o },
                reasoningTokens = u.reasoningTokens,
            ),
            completeness = u.completeness,
            raw = u.raw?.json,
        )
    }

    fun toAiError(e: ProtocolError, modelName: String): AiError {
        val m = e.message
        return when (e.kind) {
            ErrorKind.InvalidRequest -> AiError.InvalidRequest(m)
            ErrorKind.Unauthorized -> AiError.Unauthorized(m)
            ErrorKind.Forbidden -> AiError.Forbidden(m)
            ErrorKind.NotFound -> if (e.code == "model_not_found" || m.contains("model", ignoreCase = true)) AiError.ModelNotFound(modelName, m) else AiError.InvalidRequest(m)
            ErrorKind.RateLimited -> AiError.RateLimited(retryAfterMillis = null, message = m)
            ErrorKind.ContextLengthExceeded -> AiError.ContextLengthExceeded(m)
            ErrorKind.ContentFilter -> AiError.ContentFilter(m)
            ErrorKind.Overloaded -> AiError.ServerError(e.status?.takeIf { it >= 500 } ?: 503, m)
            ErrorKind.ServerError -> AiError.ServerError(e.status?.takeIf { it >= 500 } ?: 500, m)
            ErrorKind.Timeout -> AiError.Timeout(m, null)
            ErrorKind.Network -> AiError.Network(m, null)
            ErrorKind.Unknown -> AiError.Unknown(if (e.status != null) "HTTP ${e.status}: $m" else m, null)
        }
    }

    /** 审批被拒或未授权时交给模型的说明。 */
    fun deniedJson(reason: String?) = buildJsonObject { put("error", "tool_execution_denied"); reason?.let { put("message", it) } }

    val COMPLETE_ZERO = Usage(completeness = UsageCompleteness.COMPLETE)
}
