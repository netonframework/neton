package neton.ai.adapter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiStreamEvent
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.ToolChoice
import neton.ai.protocol.error.ErrorKind
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.ir.*
import neton.ai.protocol.usage.Usage
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse

/** SDK 类型 ↔ 协议层 IR（spec §10：SDK 建立在协议层 IR 之上）。 */
internal object SdkIr {
    private val json = Json { ignoreUnknownKeys = true }

    fun toChatRequest(model: String, req: ProviderCallRequest, stream: Boolean, streamUsage: Boolean? = null): ChatRequest {
        val instructions = mutableListOf<Instruction>()
        val messages = mutableListOf<Message>()
        for (m in req.messages) {
            when (m.role) {
                AiRole.System -> if (messages.isEmpty()) instructions += Instruction(m.text)
                    else messages += Message(Role.System, listOf(TextBlock(m.text)))
                AiRole.User -> messages += Message(Role.User, m.content.mapNotNull(::toBlock))
                AiRole.Assistant -> messages += Message(Role.Assistant, m.content.mapNotNull(::toBlock))
                AiRole.Tool -> messages += Message(Role.Tool, listOf(ToolResultBlock(
                    callId = m.toolCallId ?: error("Tool message must have toolCallId"),
                    content = m.content.mapNotNull(::toBlock).filter { it !is ToolCallBlock && it !is ReasoningBlock },
                    isError = m.toolResultIsError == true,
                )))
            }
        }
        val toolsActive = req.toolChoice !is ToolChoice.None
        return ChatRequest(
            model = model,
            instructions = instructions,
            messages = messages,
            tools = if (toolsActive) req.tools.map { t ->
                FunctionTool(t.name, t.description, runCatching { json.parseToJsonElement(t.inputSchemaJson) }.getOrNull())
            } else emptyList(),
            options = req.options.copy(
                temperature = req.temperature ?: req.options.temperature,
                maxOutputTokens = req.maxTokens ?: req.options.maxOutputTokens,
                topP = req.topP ?: req.options.topP,
                stop = req.stopSequences.ifEmpty { req.options.stop },
                toolChoice = if (req.tools.isEmpty() || !toolsActive) null else toIrChoice(req.toolChoice),
            ),
            stream = stream,
            streamUsage = if (stream) streamUsage else null,
        )
    }

    private fun toIrChoice(c: ToolChoice): neton.ai.protocol.ir.ToolChoice = when (c) {
        ToolChoice.Auto -> neton.ai.protocol.ir.ToolChoice.Auto
        ToolChoice.None -> neton.ai.protocol.ir.ToolChoice.None
        ToolChoice.Required -> neton.ai.protocol.ir.ToolChoice.Required
        is ToolChoice.Named -> neton.ai.protocol.ir.ToolChoice.Named(c.name)
    }

    private fun toBlock(c: AiContent): Block? = when (c) {
        is AiContent.Text -> TextBlock(c.text, metadata = c.metadata)
        is AiContent.Image -> ImageBlock(c.source, c.detail, c.metadata)
        is AiContent.File -> FileBlock(c.source, c.filename, c.metadata)
        is AiContent.Reasoning -> ReasoningBlock(c.text, c.signature, c.redactedData, c.encryptedContent, metadata = c.metadata)
        is AiContent.ToolCall -> ToolCallBlock(c.call.id, c.call.name, c.call.argumentsJson, metadata = c.metadata)
    }

    /** IR 块 → SDK 内容；SDK 表达不了的块（托管工具、原样块）保留不了，丢弃。 */
    fun toContent(b: Block): AiContent? = when (b) {
        is TextBlock -> AiContent.Text(b.text, b.metadata)
        is ImageBlock -> AiContent.Image(b.source, b.detail, b.metadata)
        is FileBlock -> AiContent.File(b.source, b.filename, b.metadata)
        is ReasoningBlock -> AiContent.Reasoning(b.text.ifEmpty { b.summary.joinToString("\n") }, b.signature, b.redactedData, b.encryptedContent, b.metadata)
        is ToolCallBlock -> AiContent.ToolCall(AiToolCall(b.id, b.name, b.arguments.ifBlank { "{}" }), b.metadata)
        is RefusalBlock -> AiContent.Text(b.text, b.metadata)
        is ToolResultBlock, is HostedToolBlock, is RawBlock -> null
    }

    fun fromChatResponse(r: ChatResponse): ProviderCallResponse {
        val content = r.content.mapNotNull(::toContent)
        val message = AiMessage(AiRole.Assistant, content)
        return ProviderCallResponse(
            message = message,
            text = message.text,
            toolCalls = message.toolCalls,
            usage = r.usage?.let(::toUsage),
            finishReason = toFinish(r.finishReason, message.toolCalls.isNotEmpty()),
        )
    }

    fun toFinish(r: FinishReason?, hasToolCalls: Boolean): AiFinishReason = when (r) {
        FinishReason.Stop -> if (hasToolCalls) AiFinishReason.ToolCalls else AiFinishReason.Stop
        FinishReason.Length -> AiFinishReason.Length
        FinishReason.ToolCalls -> AiFinishReason.ToolCalls
        FinishReason.ContentFilter, FinishReason.Refusal -> AiFinishReason.ContentFilter
        FinishReason.PauseTurn, FinishReason.Error, FinishReason.Other, null -> if (hasToolCalls) AiFinishReason.ToolCalls else AiFinishReason.Other
    }

    fun toUsage(u: Usage): AiUsage? {
        if (!u.hasAnyCount) return null
        return AiUsage(
            inputTokens = u.inputTokens?.toInt(),
            outputTokens = u.outputTokens?.toInt(),
            totalTokens = totalFromRaw(u),
            cachedInputTokens = u.cacheReadTokens?.toInt(),
            cacheWriteTokens = u.cacheWriteTokens?.toInt(),
            reasoningTokens = u.reasoningTokens?.toInt(),
            completeness = u.completeness,
        )
    }

    /** total 只取上游明确给出的值，框架不推算（spec §7）。 */
    private fun totalFromRaw(u: Usage): Int? {
        val raw = u.raw?.json ?: return null
        val v = raw["total_tokens"] ?: raw["totalTokenCount"] ?: return null
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
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
}

/**
 * IR 流事件 → SDK 流事件。不发 Started / ToolResultReady（由 AiClient 负责）；
 * 收尾时按块的原始顺序组装 assistant 消息，发出唯一的 Completed。
 */
internal class SdkStreamAdapter(private val modelName: String) {
    private class Building(val start: Block) {
        val text = StringBuilder()
        val args = StringBuilder()
        var signature: String? = (start as? ReasoningBlock)?.signature
        var stopMetadata: ProviderMetadata = ProviderMetadata.EMPTY
    }

    private val blocks = LinkedHashMap<Int, Building>()
    private var usage: Usage? = null
    private var finish: FinishReason? = null
    private var failed = false

    fun accept(e: StreamEvent): List<AiStreamEvent> {
        if (failed) return emptyList()
        val out = mutableListOf<AiStreamEvent>()
        when (e) {
            is StreamEvent.MessageStart -> {}
            is StreamEvent.BlockStart -> {
                val b = Building(e.block)
                blocks[e.index] = b
                when (val s = e.block) {
                    is TextBlock -> if (s.text.isNotEmpty()) { b.text.append(s.text); out += AiStreamEvent.TextDelta(s.text) }
                    is RefusalBlock -> if (s.text.isNotEmpty()) { b.text.append(s.text); out += AiStreamEvent.TextDelta(s.text) }
                    is ReasoningBlock -> if (s.text.isNotEmpty()) { b.text.append(s.text); out += AiStreamEvent.ReasoningDelta(s.text) }
                    is ToolCallBlock -> {
                        out += AiStreamEvent.ToolCallStarted(s.id, s.name.ifEmpty { null })
                        if (s.arguments.isNotEmpty()) { b.args.append(s.arguments); out += AiStreamEvent.ToolCallArgumentsDelta(s.id, s.arguments) }
                    }
                    else -> {}
                }
            }
            is StreamEvent.BlockDelta -> {
                val b = blocks[e.index] ?: return out
                when (val d = e.delta) {
                    is Delta.Text -> { b.text.append(d.text); out += AiStreamEvent.TextDelta(d.text) }
                    is Delta.Reasoning -> { b.text.append(d.text); out += AiStreamEvent.ReasoningDelta(d.text) }
                    is Delta.ReasoningSummary -> { b.text.append(d.text); out += AiStreamEvent.ReasoningDelta(d.text) }
                    is Delta.Signature -> b.signature = (b.signature ?: "") + d.signature
                    is Delta.Arguments -> {
                        b.args.append(d.json)
                        (b.start as? ToolCallBlock)?.let { out += AiStreamEvent.ToolCallArgumentsDelta(it.id, d.json) }
                    }
                    is Delta.Citation -> {}
                }
            }
            is StreamEvent.BlockStop -> {
                val b = blocks[e.index] ?: return out
                b.stopMetadata = e.metadata
                (finalContent(b) as? AiContent.ToolCall)?.let { tc ->
                    // ToolCallReady 的约定是参数为合法 JSON；模型给出坏参数时整条流失败，不交给执行器
                    if (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(tc.call.argumentsJson) }.isFailure) {
                        failed = true
                        out += AiStreamEvent.Failed(AiError.Unknown("model produced invalid JSON arguments for tool '${tc.call.name}'", null))
                        return out
                    }
                    out += AiStreamEvent.ToolCallReady(tc.call)
                }
            }
            is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(e.usage) ?: e.usage
            is StreamEvent.Finish -> finish = e.reason
            is StreamEvent.Error -> {
                failed = true
                out += AiStreamEvent.Failed(SdkIr.toAiError(e.error, modelName))
            }
        }
        return out
    }

    private fun finalContent(b: Building): AiContent? {
        val meta = mergeMeta(b.start.metadata, b.stopMetadata)
        return when (val s = b.start) {
            is TextBlock -> AiContent.Text(b.text.toString(), meta)
            is RefusalBlock -> AiContent.Text(b.text.toString(), meta)
            is ReasoningBlock -> AiContent.Reasoning(b.text.toString(), b.signature?.takeIf { it.isNotEmpty() }, s.redactedData,
                meta.string(ProviderMetadata.OPENAI, "encryptedContent") ?: s.encryptedContent, meta)
            is ToolCallBlock -> AiContent.ToolCall(AiToolCall(s.id, s.name, b.args.toString().ifBlank { "{}" }), meta)
            else -> null
        }
    }

    private fun mergeMeta(a: ProviderMetadata, b: ProviderMetadata): ProviderMetadata {
        var m = a
        for ((ns, obj) in b.namespaces) for ((k, v) in obj) m = m.with(ns, k, v)
        return m
    }

    /** 上游结束：[completed] 为 false 表示截断，用量记 PARTIAL。 */
    fun finish(completed: Boolean): List<AiStreamEvent> {
        if (failed) return emptyList()
        val content = blocks.values.mapNotNull(::finalContent).filter { !(it is AiContent.Text && it.text.isEmpty()) }
        val message = AiMessage(AiRole.Assistant, content)
        val u = usage?.let { if (completed) it else it.copy(completeness = neton.ai.protocol.usage.UsageCompleteness.PARTIAL) }
        return listOf(AiStreamEvent.Completed(
            message = message,
            text = message.text,
            usage = u?.let(SdkIr::toUsage),
            finishReason = SdkIr.toFinish(finish, message.toolCalls.isNotEmpty()),
        ))
    }
}

internal fun JsonObject.withoutKey(key: String): JsonObject = JsonObject(this - key)
