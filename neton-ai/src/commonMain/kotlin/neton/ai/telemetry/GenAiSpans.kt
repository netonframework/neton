package neton.ai.telemetry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.ai.AiContent
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiToolOutput
import neton.ai.AiUsage
import neton.ai.kind
import kotlin.random.Random
import kotlin.time.Clock

/** 一个结束了的 span，属性名按 OpenTelemetry GenAI 语义约定（[GenAiSpans.SEMCONV_VERSION]）。 */
data class AiSpan(
    /** 32 位十六进制。 */
    val traceId: String,
    /** 16 位十六进制。 */
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val kind: AiSpanKind,
    val startEpochNanos: Long,
    val endEpochNanos: Long,
    /** 值为 String、Long、Double、Boolean 或 List<String>。 */
    val attributes: Map<String, Any>,
    val events: List<AiSpanEvent> = emptyList(),
    /** 失败时的 `error.type`；成功为 null。 */
    val errorType: String? = null,
    val statusMessage: String? = null,
)

data class AiSpanEvent(val name: String, val epochNanos: Long, val attributes: Map<String, Any>)

enum class AiSpanKind { Internal, Client }

/** span 导出：宿主把 [AiSpan] 转成自己的追踪后端格式（OTLP 等）。在调用协程里执行，耗时操作请自行排队。 */
fun interface AiSpanExporter {
    suspend fun export(span: AiSpan)
}

/**
 * 把遥测事件转成 OpenTelemetry GenAI 语义约定的 span：
 *
 * - 文本生成（generate / stream）：根 span `invoke_agent [functionId]`，每次模型调用一个 `chat {model}` 子 span（CLIENT），
 *   每次工具执行一个 `execute_tool {tool}` 子 span；重试与回退记为根 span 或模型调用 span 上的事件。
 * - 嵌入、图片等：单个 span，如 `embeddings {model}`。
 * - 请求 metadata 里有 W3C `traceparent` 时，根 span 挂到它下面，与入站请求的追踪连起来。
 * - 内容属性（`gen_ai.input.messages`、`gen_ai.output.messages`、工具参数与结果）只在事件里带了内容时才出现，
 *   即只在打开 [AiTelemetrySettings.recordInputs] / [AiTelemetrySettings.recordOutputs] 时。
 *
 * 语义约定仍在演进，字段名只在这个适配器里出现；版本见 [SEMCONV_VERSION]。
 */
class GenAiSpans(
    private val exporter: AiSpanExporter,
    private val clockNanos: () -> Long = { Clock.System.now().let { it.epochSeconds * 1_000_000_000 + it.nanosecondsOfSecond } },
    private val random: Random = Random.Default,
) : AiTelemetry {

    private class Open(
        val spanId: String,
        val parentId: String?,
        val name: String,
        val kind: AiSpanKind,
        val start: Long,
        val attributes: MutableMap<String, Any>,
        val events: MutableList<AiSpanEvent> = mutableListOf(),
    )

    private class Operation(val traceId: String, val root: Open) {
        val modelCalls = HashMap<Int, Open>()
        val tools = HashMap<String, Open>()
    }

    private val lock = Mutex()
    private val operations = HashMap<String, Operation>()

    override suspend fun onEvent(event: AiTelemetryEvent) {
        val finished = lock.withLock { handle(event) }
        for (s in finished) exporter.export(s)
    }

    private fun handle(e: AiTelemetryEvent): List<AiSpan> {
        val id = e.context.operationId
        if (e is AiTelemetryEvent.OperationStart) {
            val parent = parseTraceparent(e.context.metadata["traceparent"])
            val traceId = parent?.first ?: hex(16)
            val (name, attrs) = rootOf(e)
            operations[id] = Operation(traceId, Open(hex(8), parent?.second, name, if (isText(e.context)) AiSpanKind.Internal else AiSpanKind.Client, clockNanos(), attrs))
            return emptyList()
        }
        val op = operations[id] ?: return emptyList()
        val now = clockNanos()
        return when (e) {
            is AiTelemetryEvent.ModelCallStart -> {
                op.modelCalls[e.stepNumber] = Open(hex(8), op.root.spanId, "chat ${e.model.modelName}", AiSpanKind.Client, now, mutableMapOf(
                    OPERATION_NAME to "chat",
                    PROVIDER_NAME to e.model.providerId,
                    REQUEST_MODEL to e.model.modelName,
                    REQUEST_STREAM to e.streaming,
                ))
                emptyList()
            }
            is AiTelemetryEvent.ModelCallRetry -> {
                op.modelCalls[e.stepNumber]?.events?.add(AiSpanEvent("neton.ai.retry", now, mapOf(
                    "neton.ai.retry.attempt" to e.attempt.toLong(), ERROR_TYPE to e.error.kind, "neton.ai.retry.delay_ms" to e.delayMs,
                )))
                emptyList()
            }
            is AiTelemetryEvent.ModelCallEnd -> {
                val span = op.modelCalls.remove(e.stepNumber) ?: return emptyList()
                e.responseId?.let { span.attributes[RESPONSE_ID] = it }
                span.attributes[FINISH_REASONS] = listOf(e.rawFinishReason ?: finishName(e.finishReason))
                putUsage(span.attributes, e.usage)
                e.timeToFirstOutputMs?.let { span.attributes["neton.ai.time_to_first_output_ms"] = it }
                e.output?.let { span.attributes[OUTPUT_MESSAGES] = messagesJson(listOf(AiMessage(neton.ai.AiRole.Assistant, it)), finishName(e.finishReason)) }
                listOf(close(op, span, now))
            }
            is AiTelemetryEvent.ModelCallError -> {
                val span = op.modelCalls.remove(e.stepNumber) ?: return emptyList()
                listOf(close(op, span, now, e.error.kind, e.error.message))
            }
            is AiTelemetryEvent.Fallback -> {
                op.root.events += AiSpanEvent("neton.ai.fallback", now, mapOf("neton.ai.fallback.from" to e.from.toString(), ERROR_TYPE to e.error.kind))
                emptyList()
            }
            is AiTelemetryEvent.ToolStart -> {
                op.tools[e.toolCallId] = Open(hex(8), op.root.spanId, "execute_tool ${e.toolName}", AiSpanKind.Internal, now, mutableMapOf<String, Any>(
                    OPERATION_NAME to "execute_tool",
                    TOOL_NAME to e.toolName,
                    TOOL_CALL_ID to e.toolCallId,
                    TOOL_TYPE to "function",
                ).also { a -> e.input?.let { a[TOOL_ARGUMENTS] = it.toString() } })
                emptyList()
            }
            is AiTelemetryEvent.ToolEnd -> {
                val span = op.tools.remove(e.toolCallId) ?: return emptyList()
                e.output?.let { span.attributes[TOOL_RESULT] = toolOutputText(it) }
                listOf(close(op, span, now, if (e.isError) "tool_error" else null))
            }
            is AiTelemetryEvent.OperationEnd -> {
                operations.remove(id)
                putUsage(op.root.attributes, e.usage)
                e.finishReason?.let { op.root.attributes[FINISH_REASONS] = listOf(finishName(it)) }
                closeAll(op, now) + close(op, op.root, now)
            }
            is AiTelemetryEvent.OperationError -> {
                operations.remove(id)
                closeAll(op, now, "cancelled") + close(op, op.root, now, e.error.kind, e.error.message)
            }
            else -> emptyList()
        }
    }

    private fun isText(c: AiTelemetryContext) = c.operation == AiOperation.Generate || c.operation == AiOperation.Stream

    private fun rootOf(e: AiTelemetryEvent.OperationStart): Pair<String, MutableMap<String, Any>> {
        val c = e.context
        val attrs = mutableMapOf<String, Any>()
        e.model?.let { attrs[PROVIDER_NAME] = it.providerId; attrs[REQUEST_MODEL] = it.modelName }
        c.functionId?.let { attrs[AGENT_NAME] = it }
        if (isText(c)) {
            attrs[OPERATION_NAME] = "invoke_agent"
            attrs[REQUEST_STREAM] = c.operation == AiOperation.Stream
            e.input?.let { attrs[INPUT_MESSAGES] = messagesJson(it, null) }
            return (if (c.functionId != null) "invoke_agent ${c.functionId}" else "invoke_agent") to attrs
        }
        val (op, output) = when (c.operation) {
            AiOperation.Embed -> "embeddings" to null
            AiOperation.Image -> "generate_content" to "image"
            AiOperation.Speech -> "generate_content" to "speech"
            AiOperation.Transcribe -> "transcribe" to "text"
            AiOperation.Rerank -> "rerank" to null
            else -> c.operation.id to null
        }
        attrs[OPERATION_NAME] = op
        output?.let { attrs[OUTPUT_TYPE] = it }
        return (if (e.model != null) "$op ${e.model.modelName}" else op) to attrs
    }

    private fun close(op: Operation, s: Open, end: Long, errorType: String? = null, message: String? = null): AiSpan {
        errorType?.let { s.attributes[ERROR_TYPE] = it }
        return AiSpan(op.traceId, s.spanId, s.parentId, s.name, s.kind, s.start, end, s.attributes.toMap(), s.events.toList(), errorType, message)
    }

    /** 操作结束时还开着的子 span（被取消的模型调用或工具）一并结束。 */
    private fun closeAll(op: Operation, end: Long, errorType: String? = "cancelled"): List<AiSpan> {
        val out = (op.modelCalls.values + op.tools.values).map { close(op, it, end, errorType) }
        op.modelCalls.clear(); op.tools.clear()
        return out
    }

    private fun putUsage(a: MutableMap<String, Any>, u: AiUsage) {
        u.inputTokens?.let { a[USAGE_INPUT] = it }
        u.outputTokens?.let { a[USAGE_OUTPUT] = it }
        u.inputTokenDetails.cacheReadTokens?.let { a[USAGE_CACHE_READ] = it }
        u.inputTokenDetails.cacheWriteTokens?.let { a[USAGE_CACHE_CREATION] = it }
        u.outputTokenDetails.reasoningTokens?.let { a[USAGE_REASONING] = it }
    }

    private fun finishName(r: AiFinishReason) = when (r) {
        AiFinishReason.Stop -> "stop"
        AiFinishReason.Length -> "length"
        AiFinishReason.ToolCalls -> "tool_calls"
        AiFinishReason.ContentFilter -> "content_filter"
        AiFinishReason.Error -> "error"
        AiFinishReason.Other -> "other"
    }

    /** 语义约定的消息格式：`[{role, parts:[{type, ...}], finish_reason?}]`。媒体内容只记类型，不记数据。 */
    private fun messagesJson(messages: List<AiMessage>, finishReason: String?): String = buildJsonArray {
        for (m in messages) add(buildJsonObject {
            put("role", m.role.name.lowercase())
            put("parts", JsonArray(m.content.mapNotNull(::part)))
            finishReason?.let { put("finish_reason", it) }
        })
    }.toString()

    private fun part(c: AiContent): JsonObject? = when (c) {
        is AiContent.Text -> buildJsonObject { put("type", "text"); put("content", c.text) }
        is AiContent.Reasoning -> buildJsonObject { put("type", "reasoning"); put("content", c.text) }
        is AiContent.ToolCall -> buildJsonObject { put("type", "tool_call"); put("id", c.call.id); put("name", c.call.name); put("arguments", c.call.argumentsJson) }
        is AiContent.ToolResult -> buildJsonObject { put("type", "tool_call_response"); put("id", c.toolCallId); put("response", toolOutputText(c.output)) }
        is AiContent.Image -> buildJsonObject { put("type", "blob"); put("modality", "image") }
        is AiContent.File -> buildJsonObject { put("type", "blob"); put("modality", "file") }
        is AiContent.Refusal -> buildJsonObject { put("type", "text"); put("content", c.text) }
        else -> null
    }

    private fun toolOutputText(o: AiToolOutput): String = when (o) {
        is AiToolOutput.Json -> o.value.toString()
        is AiToolOutput.ErrorJson -> o.value.toString()
        else -> o.asText()
    }

    private fun hex(bytes: Int): String = buildString {
        repeat(bytes) { append(random.nextInt(256).toString(16).padStart(2, '0')) }
    }

    /** W3C traceparent `00-<trace-id>-<parent-id>-<flags>` → (traceId, parentSpanId)。 */
    private fun parseTraceparent(v: String?): Pair<String, String>? {
        val parts = v?.trim()?.split('-') ?: return null
        if (parts.size < 4 || parts[1].length != 32 || parts[2].length != 16) return null
        if (!parts[1].all { it in HEX } || !parts[2].all { it in HEX } || parts[1].all { it == '0' } || parts[2].all { it == '0' }) return null
        return parts[1] to parts[2]
    }

    companion object {
        /** 对齐的 OpenTelemetry GenAI 语义约定版本。 */
        const val SEMCONV_VERSION = "1.37.0"
        private const val HEX = "0123456789abcdef"

        const val OPERATION_NAME = "gen_ai.operation.name"
        const val PROVIDER_NAME = "gen_ai.provider.name"
        const val REQUEST_MODEL = "gen_ai.request.model"
        const val REQUEST_STREAM = "gen_ai.request.stream"
        const val AGENT_NAME = "gen_ai.agent.name"
        const val OUTPUT_TYPE = "gen_ai.output.type"
        const val RESPONSE_ID = "gen_ai.response.id"
        const val FINISH_REASONS = "gen_ai.response.finish_reasons"
        const val USAGE_INPUT = "gen_ai.usage.input_tokens"
        const val USAGE_OUTPUT = "gen_ai.usage.output_tokens"
        const val USAGE_CACHE_READ = "gen_ai.usage.cache_read.input_tokens"
        const val USAGE_CACHE_CREATION = "gen_ai.usage.cache_creation.input_tokens"
        /** 语义约定尚未定义推理 token，放在自己的命名空间。 */
        const val USAGE_REASONING = "neton.ai.usage.reasoning_tokens"
        const val INPUT_MESSAGES = "gen_ai.input.messages"
        const val OUTPUT_MESSAGES = "gen_ai.output.messages"
        const val TOOL_NAME = "gen_ai.tool.name"
        const val TOOL_CALL_ID = "gen_ai.tool.call.id"
        const val TOOL_TYPE = "gen_ai.tool.type"
        const val TOOL_ARGUMENTS = "gen_ai.tool.call.arguments"
        const val TOOL_RESULT = "gen_ai.tool.call.result"
        const val ERROR_TYPE = "error.type"
    }
}
