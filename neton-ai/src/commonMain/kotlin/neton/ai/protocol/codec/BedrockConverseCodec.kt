package neton.ai.protocol.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.diag.Diagnostics
import neton.ai.protocol.error.ErrorKind
import neton.ai.protocol.error.ProtocolError
import neton.ai.protocol.ir.*
import neton.ai.protocol.stream.BlockSequencer
import neton.ai.protocol.transport.SseEvent
import neton.ai.protocol.transport.SseWriter
import neton.ai.protocol.usage.RawUsage
import neton.ai.protocol.usage.Usage
import neton.ai.protocol.usage.UsageCompleteness

/**
 * Amazon Bedrock Converse（`/model/{id}/converse`、`/converse-stream`）：Bedrock 上所有模型共用的统一接口。
 *
 * - 模型 ID 与是否流式在路径上；
 * - 流式响应是 AWS event-stream，经 [neton.ai.protocol.transport.aws.EventStreamDecoder] 解成
 *   `event: <事件名>` + JSON 负载后交给本解码器；编码器输出同样的文本形式，需要二进制帧时由宿主封装；
 * - `cachePoint` 块表示「在此之前的内容可缓存」：解码时挂到前一个块的 `bedrock.cachePoint` 元数据上，
 *   编码时在带 `bedrock.cachePoint` 或 Anthropic `cache_control` 的块后面补一个 cachePoint。
 */
@neton.ai.ExperimentalNetonAiApi
object BedrockConverseCodec : ProtocolCodec {
    override val format = WireFormat.BEDROCK_CONVERSE
    private val F = WireFormat.BEDROCK_CONVERSE
    private const val NS = "bedrock"
    private const val CACHE_POINT = "cachePoint"

    private val REQUEST_KEYS = setOf("modelId", "messages", "system", "inferenceConfig", "toolConfig")
    private val IMAGE_FORMATS = setOf("png", "jpeg", "gif", "webp")
    private val DOC_FORMATS = setOf("pdf", "csv", "doc", "docx", "xls", "xlsx", "html", "txt", "md")

    // ───────────────────────── 请求 ─────────────────────────

    override fun decodeRequest(body: JsonObject, diagnostics: Diagnostics): ChatRequest {
        val instructions = mutableListOf<Instruction>()
        body.arr("system")?.objects()?.forEach { s ->
            when {
                s.containsKey("text") -> instructions += Instruction(s.str("text") ?: "")
                s.containsKey(CACHE_POINT) && instructions.isNotEmpty() ->
                    instructions[instructions.lastIndex] = instructions.last().let { it.copy(metadata = it.metadata.with(NS, CACHE_POINT, s[CACHE_POINT])) }
            }
        }
        val messages = (body.arr("messages") ?: JsonArray(emptyList())).objects().mapIndexed { i, m ->
            Message(if (m.str("role") == "assistant") Role.Assistant else Role.User, decodeContent(m.arr("content"), "messages[$i].content", diagnostics))
        }
        val inf = body.obj("inferenceConfig") ?: JsonObject(emptyMap())
        val tc = body.obj("toolConfig")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, REQUEST_KEYS)
        JsonObject(inf.extraFields(setOf("maxTokens", "temperature", "topP", "stopSequences"))).takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "inferenceExtra", it) }
        return ChatRequest(
            model = body.str("modelId"),
            instructions = instructions,
            messages = messages,
            tools = tc?.arr("tools")?.objects()?.mapNotNull { t ->
                t.obj("toolSpec")?.let { s -> FunctionTool(s.str("name") ?: "", s.str("description"), s.obj("inputSchema")?.get("json")) }
                    ?: if (t.containsKey(CACHE_POINT)) null else HostedTool(t.keys.firstOrNull() ?: "", t, F)
            } ?: emptyList(),
            options = CallOptions(
                maxOutputTokens = inf.int("maxTokens"),
                temperature = inf.double("temperature"),
                topP = inf.double("topP"),
                stop = inf.arr("stopSequences")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                toolChoice = tc?.obj("toolChoice")?.let { c ->
                    when {
                        c.containsKey("auto") -> ToolChoice.Auto
                        c.containsKey("any") -> ToolChoice.Required
                        c.containsKey("tool") -> c.obj("tool")?.str("name")?.let { ToolChoice.Named(it) }
                        else -> null
                    }
                },
            ),
            metadata = meta,
        )
    }

    private fun decodeContent(content: JsonArray?, path: String, d: Diagnostics): List<Block> {
        val out = mutableListOf<Block>()
        content?.objects()?.forEachIndexed { i, b ->
            if (b.containsKey(CACHE_POINT)) {
                if (out.isNotEmpty()) out[out.lastIndex] = withMeta(out.last(), out.last().metadata.with(NS, CACHE_POINT, b[CACHE_POINT]))
                return@forEachIndexed
            }
            decodeBlock(b, "$path[$i]", d)?.let { out += it }
        }
        return out
    }

    private fun decodeBlock(b: JsonObject, path: String, d: Diagnostics): Block? {
        b.str("text")?.let { return TextBlock(it) }
        b.obj("image")?.let { img ->
            val bytes = img.obj("source")?.str("bytes") ?: return RawBlock("image", b, F)
            return ImageBlock(MediaSource.Base64(bytes, "image/${img.str("format") ?: "png"}"))
        }
        b.obj("document")?.let { doc ->
            val bytes = doc.obj("source")?.str("bytes") ?: return RawBlock("document", b, F)
            return FileBlock(MediaSource.Base64(bytes, docMediaType(doc.str("format"))), doc.str("name"))
        }
        b.obj("toolUse")?.let { tu ->
            return ToolCallBlock(tu.str("toolUseId") ?: "", tu.str("name") ?: "", (tu["input"] ?: JsonObject(emptyMap())).toString())
        }
        b.obj("toolResult")?.let { tr ->
            val inner = tr.arr("content")?.objects()?.mapNotNull { c ->
                c.str("text")?.let { TextBlock(it) } ?: c["json"]?.let { TextBlock(it.toString()) }
                    ?: c.obj("image")?.let { decodeBlock(c, path, d) }
            } ?: emptyList()
            return ToolResultBlock(tr.str("toolUseId") ?: "", inner, isError = tr.str("status") == "error")
        }
        b.obj("reasoningContent")?.let { rc ->
            rc.str("redactedContent")?.let { return ReasoningBlock(redactedData = it) }
            val rt = rc.obj("reasoningText")
            return ReasoningBlock(rt?.str("text") ?: "", signature = rt?.str("signature"))
        }
        return RawBlock(b.keys.firstOrNull() ?: "", b, F)
    }

    private fun withMeta(b: Block, m: ProviderMetadata): Block = when (b) {
        is TextBlock -> b.copy(metadata = m)
        is ImageBlock -> b.copy(metadata = m)
        is FileBlock -> b.copy(metadata = m)
        is ToolCallBlock -> b.copy(metadata = m)
        is ToolResultBlock -> b.copy(metadata = m)
        is ReasoningBlock -> b.copy(metadata = m)
        is RefusalBlock -> b.copy(metadata = m)
        is HostedToolBlock -> b.copy(metadata = m)
        is RawBlock -> b.copy(metadata = m)
    }

    private fun docMediaType(format: String?): String = when (format) {
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    private fun docFormat(mediaType: String?): String? = when (mediaType) {
        "application/pdf" -> "pdf"
        "text/csv" -> "csv"
        "text/html" -> "html"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        else -> null
    }

    private fun wantsCachePoint(m: ProviderMetadata): Boolean =
        m[NS, CACHE_POINT] != null || m[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null

    private val CACHE_POINT_BLOCK = buildJsonObject { put(CACHE_POINT, buildJsonObject { put("type", "default") }) }

    override fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics): JsonObject = tracking {
        val d = diagnostics
        val o = request.options
        val system = mutableListOf<JsonElement>()
        request.instructions.forEach { ins ->
            system += buildJsonObject { put("text", ins.text) }
            if (wantsCachePoint(ins.metadata)) system += CACHE_POINT_BLOCK
        }
        val messages = mutableListOf<JsonObject>()
        request.messages.forEachIndexed { i, m ->
            if (m.role == Role.System || m.role == Role.Developer) {
                d.warn(DiagnosticCodes.INSTRUCTION_MOVED, "messages[$i]", "mid-conversation system message moved into system")
                system += buildJsonObject { put("text", m.content.plainText("\n")) }
                return@forEachIndexed
            }
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val blocks = mutableListOf<JsonElement>()
            // toolResult 放在 user 消息最前面
            val ordered = if (role == "user") m.content.sortedBy { if (it is ToolResultBlock) 0 else 1 } else m.content
            ordered.forEachIndexed { j, b ->
                encodeBlock(b, "messages[$i].content[$j]", d, m.role)?.let { blocks += it; if (wantsCachePoint(b.metadata)) blocks += CACHE_POINT_BLOCK }
            }
            if (blocks.isEmpty()) return@forEachIndexed
            // 相邻同角色合并（Converse 要求 user/assistant 交替）
            val last = messages.lastOrNull()
            if (last != null && last.str("role") == role) messages[messages.lastIndex] = JsonObject(last + ("content" to JsonArray(last.arr("content")!! + blocks)))
            else messages += buildJsonObject { put("role", role); put("content", JsonArray(blocks)) }
        }
        put("messages", JsonArray(messages))
        if (system.isNotEmpty()) put("system", JsonArray(system))
        val inf = tracking {
            put("maxTokens", o.maxOutputTokens)
            put("temperature", o.temperature)
            put("topP", o.topP)
            if (o.stop.isNotEmpty()) put("stopSequences", JsonArray(o.stop.map(::JsonPrimitive)))
            (request.metadata[NS, "inferenceExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
        }
        if (inf.isNotEmpty()) put("inferenceConfig", inf)
        val tools = request.tools.mapIndexedNotNull { i, t ->
            when (t) {
                is FunctionTool -> buildJsonObject {
                    put("toolSpec", buildJsonObject {
                        put("name", t.name)
                        t.description?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                        put("inputSchema", buildJsonObject { put("json", t.parameters ?: buildJsonObject { put("type", "object") }) })
                    })
                }
                is HostedTool -> if (t.sourceFormat == F) t.raw else {
                    d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, "tools[$i]", "hosted tool '${t.type}' has no Converse equivalent"); null
                }
                is CustomTool -> { d.error(DiagnosticCodes.CUSTOM_TOOL_AS_FUNCTION, "tools[$i]", "custom tools are not supported by Converse"); null }
            }
        }
        if (tools.isNotEmpty()) put("toolConfig", buildJsonObject {
            put("tools", JsonArray(tools))
            when (val c = o.toolChoice) {
                ToolChoice.Auto -> put("toolChoice", buildJsonObject { put("auto", buildJsonObject { }) })
                ToolChoice.Required -> put("toolChoice", buildJsonObject { put("any", buildJsonObject { }) })
                is ToolChoice.Named -> put("toolChoice", buildJsonObject { put("tool", buildJsonObject { put("name", c.name) }) })
                ToolChoice.None, null -> {}
            }
        })
        if (o.toolChoice == ToolChoice.None && request.tools.isNotEmpty()) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "tool_choice", "Converse has no 'none' tool choice; tools were still sent")
        if (o.reasoning != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "reasoning", "reasoning options are model-specific on Converse; pass them via additionalModelRequestFields")
        if (o.responseFormat != null && o.responseFormat != ResponseFormat.Text) d.error(DiagnosticCodes.UNSUPPORTED_OPTION, "response_format", "Converse has no structured output parameter")
        putExtras(request.metadata, F)
    }

    /** [forOutput]：编给客户端的响应。没有签名的推理在请求里不能发给 Converse，在响应里可以原样展示。 */
    private fun encodeBlock(b: Block, path: String, d: Diagnostics, role: Role, forOutput: Boolean = false): JsonObject? = when (b) {
        is TextBlock -> buildJsonObject { put("text", b.text) }
        is ImageBlock -> when (val s = b.source) {
            is MediaSource.Base64 -> {
                val fmt = s.mediaType?.removePrefix("image/")?.let { if (it == "jpg") "jpeg" else it }
                if (fmt in IMAGE_FORMATS) buildJsonObject {
                    put("image", buildJsonObject { put("format", fmt!!); put("source", buildJsonObject { put("bytes", s.data) }) })
                } else { d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, "Converse images must be png/jpeg/gif/webp"); null }
            }
            else -> { d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, "Converse needs inline image bytes (URLs need a host media resolver)"); null }
        }
        is FileBlock -> {
            val s = b.source
            val fmt = docFormat(s.mediaType)
            if (s is MediaSource.Base64 && fmt in DOC_FORMATS) buildJsonObject {
                put("document", buildJsonObject {
                    put("format", fmt!!); put("name", (b.filename ?: "document").replace(Regex("[^A-Za-z0-9 ()\\[\\]-]"), "-"))
                    put("source", buildJsonObject { put("bytes", s.data) })
                })
            } else { d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, "unsupported Converse document ${s.mediaType}"); null }
        }
        is ToolCallBlock -> buildJsonObject {
            put("toolUse", buildJsonObject {
                put("toolUseId", b.id); put("name", b.name)
                put("input", J.parseOrString(b.arguments.ifBlank { "{}" }).let { it as? JsonObject ?: buildJsonObject { put("input", b.arguments) } })
            })
        }
        is ToolResultBlock -> buildJsonObject {
            put("toolResult", buildJsonObject {
                put("toolUseId", b.callId)
                put("content", buildJsonArray {
                    b.content.forEachIndexed { j, c -> encodeBlock(c, "$path.content[$j]", d, Role.User)?.let { add(it) } }
                    if (b.content.isEmpty()) add(buildJsonObject { put("text", "") })
                })
                if (b.isError) put("status", "error")
            })
        }
        is ReasoningBlock -> when {
            role != Role.Assistant -> null
            b.redactedData != null -> buildJsonObject { put("reasoningContent", buildJsonObject { put("redactedContent", b.redactedData) }) }
            b.signature != null -> buildJsonObject {
                put("reasoningContent", buildJsonObject { put("reasoningText", buildJsonObject { put("text", b.text); put("signature", b.signature) }) })
            }
            forOutput -> b.text.ifEmpty { b.summary.joinToString("\n") }.takeIf { it.isNotEmpty() }?.let { text ->
                buildJsonObject { put("reasoningContent", buildJsonObject { put("reasoningText", buildJsonObject { put("text", text) }) }) }
            }
            else -> { d.warn(DiagnosticCodes.REASONING_DROPPED, path, "unsigned reasoning is not sent to Converse"); null }
        }
        is RefusalBlock -> buildJsonObject { put("text", b.text) }
        is HostedToolBlock -> { d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool activity dropped"); null }
        is RawBlock -> if (b.sourceFormat == F) b.raw else { d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, path, "'${b.type}' block dropped"); null }
    }

    // ───────────────────────── 响应 ─────────────────────────

    override fun decodeResponse(body: JsonObject, diagnostics: Diagnostics): ChatResponse {
        val raw = body.str("stopReason")
        return ChatResponse(
            content = decodeContent(body.obj("output")?.obj("message")?.arr("content"), "output.message.content", diagnostics),
            finishReason = raw?.let(::finishReasonOf),
            rawFinishReason = raw,
            usage = body.obj("usage")?.let { decodeUsage(it, UsageCompleteness.COMPLETE) },
            metadata = ProviderMetadata.EMPTY.withExtra(F, body, setOf("output", "stopReason", "usage")),
        )
    }

    override fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics): JsonObject = tracking {
        put("output", buildJsonObject {
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(response.content.mapIndexedNotNull { i, b -> encodeBlock(b, "content[$i]", diagnostics, Role.Assistant, forOutput = true) }))
            })
        })
        put("stopReason", stopReasonString(response.finishReason, response.rawFinishReason))
        response.usage?.let { put("usage", encodeUsage(it)) }
        putExtras(response.metadata, F)
    }

    internal fun finishReasonOf(raw: String): FinishReason = when (raw) {
        "end_turn", "stop_sequence" -> FinishReason.Stop
        "max_tokens", "model_context_window_exceeded" -> FinishReason.Length
        "tool_use" -> FinishReason.ToolCalls
        "guardrail_intervened", "content_filtered" -> FinishReason.ContentFilter
        else -> FinishReason.Other
    }

    private val STOP_REASONS = setOf("end_turn", "stop_sequence", "max_tokens", "tool_use", "guardrail_intervened", "content_filtered", "model_context_window_exceeded")

    internal fun stopReasonString(reason: FinishReason?, raw: String?): String {
        if (raw != null && raw in STOP_REASONS) return raw
        return when (reason) {
            FinishReason.Length -> "max_tokens"
            FinishReason.ToolCalls -> "tool_use"
            FinishReason.ContentFilter, FinishReason.Refusal -> "content_filtered"
            else -> "end_turn"
        }
    }

    /** Converse 的 inputTokens 不含缓存读写（与 Anthropic 相同），换算到 IR 口径时加回去。 */
    internal fun decodeUsage(u: JsonObject, completeness: UsageCompleteness): Usage {
        val read = u.long("cacheReadInputTokens")
        val write = u.long("cacheWriteInputTokens")
        return Usage(
            inputTokens = u.long("inputTokens")?.let { it + (read ?: 0) + (write ?: 0) },
            outputTokens = u.long("outputTokens"),
            cacheReadTokens = read,
            cacheWrite5mTokens = write,
            completeness = completeness,
            raw = RawUsage(F, u),
        )
    }

    internal fun encodeUsage(u: Usage): JsonObject {
        if (u.raw?.format == F) return u.raw.json
        val write = u.cacheWriteTokens
        return tracking {
            val input = u.inputTokens?.let { (it - (u.cacheReadTokens ?: 0) - (write ?: 0)).coerceAtLeast(0) }
            put("inputTokens", input ?: 0)
            put("outputTokens", u.outputTokens ?: 0)
            if (input != null && u.outputTokens != null) put("totalTokens", u.inputTokens + u.outputTokens)
            put("cacheReadInputTokens", u.cacheReadTokens)
            put("cacheWriteInputTokens", write)
        }
    }

    // ───────────────────────── 错误 ─────────────────────────

    override fun encodeError(error: ProtocolError): JsonObject = buildJsonObject { put("message", error.message) }

    override fun decodeError(status: Int, body: String): ProtocolError {
        val o = J.parseObject(body)
        val message = o?.str("message") ?: o?.str("Message") ?: errorMessageFallback(body, status)
        val kind = when {
            message.contains("too long", true) || message.contains("context", true) && message.contains("exceed", true) -> ErrorKind.ContextLengthExceeded
            status == 400 -> ErrorKind.InvalidRequest
            else -> ProtocolError.kindForStatus(status)
        }
        return ProtocolError(kind, message, status, o?.str("__type"), body, F)
    }

    // ───────────────────────── 流式 ─────────────────────────

    override fun streamDecoder(diagnostics: Diagnostics): StreamDecoder = ConverseStreamDecoder(diagnostics)
    override fun streamEncoder(diagnostics: Diagnostics): StreamEncoder = ConverseStreamEncoder(diagnostics)

    private class ConverseStreamDecoder(private val d: Diagnostics) : StreamDecoder {
        private val seq = BlockSequencer()
        private var started = false
        private var stopSeen = false
        override var completed = false
            private set

        override fun decode(event: SseEvent): List<StreamEvent> {
            val data = event.data.trim()
            if (data.isEmpty()) return emptyList()
            val obj = J.parseObject(data) ?: return malformed(seq, d, WireFormat.BEDROCK_CONVERSE).also { completed = true }
            val out = mutableListOf<StreamEvent>()
            if (!started) { started = true; out += StreamEvent.MessageStart() }
            when (event.event) {
                "messageStart" -> {}
                "contentBlockStart" -> {
                    val idx = obj.int("contentBlockIndex") ?: 0
                    val tu = obj.obj("start")?.obj("toolUse")
                    seq.ensure(idx, out, exclusive = false) { if (tu != null) ToolCallBlock(tu.str("toolUseId") ?: "", tu.str("name") ?: "", "") else TextBlock("") }
                }
                "contentBlockDelta" -> {
                    val idx = obj.int("contentBlockIndex") ?: 0
                    val delta = obj.obj("delta") ?: return out
                    val rc = delta.obj("reasoningContent")
                    when {
                        delta.str("text") != null -> seq.delta(idx, Delta.Text(delta.str("text")!!), out, exclusive = false) { TextBlock("") }
                        delta.obj("toolUse") != null -> seq.delta(idx, Delta.Arguments(delta.obj("toolUse")!!.str("input") ?: ""), out, exclusive = false) { ToolCallBlock("", "", "") }
                        rc?.str("text") != null -> seq.delta(idx, Delta.Reasoning(rc.str("text")!!), out, exclusive = false) { ReasoningBlock() }
                        rc?.str("signature") != null -> seq.delta(idx, Delta.Signature(rc.str("signature")!!), out, exclusive = false) { ReasoningBlock() }
                        rc?.str("redactedContent") != null -> seq.ensure(idx, out, exclusive = false) { ReasoningBlock(redactedData = rc.str("redactedContent")) }
                    }
                }
                "contentBlockStop" -> seq.close(obj.int("contentBlockIndex") ?: 0, out)
                "messageStop" -> {
                    seq.close(out)
                    stopSeen = true
                    obj.str("stopReason")?.let { out += StreamEvent.Finish(finishReasonOf(it), it) }
                }
                "metadata" -> {
                    obj.obj("usage")?.let { out += StreamEvent.UsageUpdate(decodeUsage(it, UsageCompleteness.COMPLETE)) }
                    if (stopSeen) completed = true
                }
                "error", "internalServerException", "modelStreamErrorException", "throttlingException", "validationException", "serviceUnavailableException" -> {
                    seq.close(out)
                    val kind = when (event.event) {
                        "throttlingException" -> ErrorKind.RateLimited
                        "validationException" -> ErrorKind.InvalidRequest
                        "serviceUnavailableException" -> ErrorKind.Overloaded
                        else -> ErrorKind.ServerError
                    }
                    out += StreamEvent.Error(ProtocolError(kind, obj.str("message") ?: obj.obj("error")?.str("message") ?: "stream error", code = event.event, rawBody = data, sourceFormat = WireFormat.BEDROCK_CONVERSE))
                    completed = true
                }
            }
            return out
        }

        override fun finish(): List<StreamEvent> {
            val out = mutableListOf<StreamEvent>()
            seq.close(out)
            // metadata 事件是可选的：见过 messageStop 就算正常结束
            if (stopSeen) completed = true
            return out
        }
    }

    /** 输出 `event: <事件名>` + JSON 负载的文本形式（与解码器的输入一致）。 */
    private class ConverseStreamEncoder(private val d: Diagnostics) : StreamEncoder {
        private val customTools = neton.ai.protocol.stream.CustomToolCallsAsFunctions(d)
        private val sequential = neton.ai.protocol.stream.SequentialBlocks()
        private var started = false
        private var done = false
        private var next = 0
        private val indexOf = HashMap<Int, Int>()
        private var usage: Usage? = null
        private var finish: StreamEvent.Finish? = null

        override fun encode(event: StreamEvent): String {
            if (done) return ""
            val sb = StringBuilder()
            for (c in customTools.accept(event)) for (e in sequential.accept(c)) one(e, sb)
            return sb.toString()
        }

        private fun emit(sb: StringBuilder, name: String, data: JsonObject) { sb.append(SseWriter.event(name, data.toString())) }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            emit(sb, "messageStart", buildJsonObject { put("role", "assistant") })
        }

        private fun one(e: StreamEvent, sb: StringBuilder) {
            if (done) return
            when (e) {
                is StreamEvent.MessageStart -> ensureStarted(sb)
                is StreamEvent.BlockStart -> {
                    ensureStarted(sb)
                    val b = e.block
                    if (b !is TextBlock && b !is ToolCallBlock && b !is ReasoningBlock && b !is RefusalBlock) return
                    val idx = next++
                    indexOf[e.index] = idx
                    if (b is ToolCallBlock) emit(sb, "contentBlockStart", buildJsonObject {
                        put("contentBlockIndex", idx)
                        put("start", buildJsonObject { put("toolUse", buildJsonObject { put("toolUseId", b.id); put("name", b.name) }) })
                    })
                    when (b) {
                        is TextBlock -> if (b.text.isNotEmpty()) delta(sb, idx, buildJsonObject { put("text", b.text) })
                        is ToolCallBlock -> if (b.arguments.isNotEmpty()) delta(sb, idx, buildJsonObject { put("toolUse", buildJsonObject { put("input", b.arguments) }) })
                        is ReasoningBlock -> if (b.text.isNotEmpty()) delta(sb, idx, buildJsonObject { put("reasoningContent", buildJsonObject { put("text", b.text) }) })
                        else -> {}
                    }
                }
                is StreamEvent.BlockDelta -> {
                    val idx = indexOf[e.index] ?: return
                    when (val dl = e.delta) {
                        is Delta.Text -> delta(sb, idx, buildJsonObject { put("text", dl.text) })
                        is Delta.Arguments -> delta(sb, idx, buildJsonObject { put("toolUse", buildJsonObject { put("input", dl.json) }) })
                        is Delta.Reasoning -> delta(sb, idx, buildJsonObject { put("reasoningContent", buildJsonObject { put("text", dl.text) }) })
                        is Delta.ReasoningSummary -> delta(sb, idx, buildJsonObject { put("reasoningContent", buildJsonObject { put("text", dl.text) }) })
                        is Delta.Signature -> delta(sb, idx, buildJsonObject { put("reasoningContent", buildJsonObject { put("signature", dl.signature) }) })
                        is Delta.Citation -> {}
                    }
                }
                is StreamEvent.BlockStop -> indexOf[e.index]?.let { emit(sb, "contentBlockStop", buildJsonObject { put("contentBlockIndex", it) }) }
                is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(e.usage) ?: e.usage
                is StreamEvent.Finish -> finish = e
                is StreamEvent.Error -> {
                    emit(sb, "internalServerException", buildJsonObject { put("message", e.error.message) })
                    done = true
                }
            }
        }

        private fun delta(sb: StringBuilder, idx: Int, delta: JsonObject) =
            emit(sb, "contentBlockDelta", buildJsonObject { put("contentBlockIndex", idx); put("delta", delta) })

        override fun finish(truncated: Boolean): String {
            if (done) return ""
            val sb = StringBuilder()
            for (e in sequential.flush()) one(e, sb)
            done = true
            if (truncated) return sb.toString()
            ensureStarted(sb)
            emit(sb, "messageStop", buildJsonObject { put("stopReason", stopReasonString(finish?.reason ?: FinishReason.Stop, finish?.raw)) })
            usage?.let { u -> emit(sb, "metadata", buildJsonObject { put("usage", encodeUsage(u)) }) }
            return sb.toString()
        }
    }
}
