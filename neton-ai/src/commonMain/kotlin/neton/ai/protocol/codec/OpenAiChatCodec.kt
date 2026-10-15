package neton.ai.protocol.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * OpenAI Chat Completions。
 *
 * 方言：推理内容读 `reasoning_content` 与 `reasoning`（兼容服务的两种写法），写 `reasoning_content`；
 * Gemini 签名走官方 OpenAI 兼容字段 `extra_content.google.thought_signature`（spec §4.5.1 载体 A）。
 */
object OpenAiChatCodec : ProtocolCodec {
    override val format = WireFormat.OPENAI_CHAT
    private val F = WireFormat.OPENAI_CHAT

    private val REQUEST_KEYS = setOf(
        "model", "messages", "tools", "tool_choice", "parallel_tool_calls", "temperature", "top_p", "top_k",
        "max_tokens", "max_completion_tokens", "stop", "seed", "presence_penalty", "frequency_penalty",
        "response_format", "reasoning_effort", "user", "n", "stream", "stream_options",
    )
    private val MESSAGE_KEYS = setOf("role", "content", "name", "tool_calls", "tool_call_id", "refusal", "reasoning_content", "reasoning")
    private val TOOL_CALL_KEYS = setOf("id", "type", "function", "custom", "extra_content", "index")
    private val TOOL_KEYS = setOf("type", "function", "custom")
    private val RESPONSE_KEYS = setOf("id", "object", "created", "model", "choices", "usage")
    private const val MAX_TOKENS_FIELD = "maxTokensField"

    // ───────────────────────── 请求 ─────────────────────────

    override fun decodeRequest(body: JsonObject, diagnostics: Diagnostics): ChatRequest {
        val instructions = mutableListOf<Instruction>()
        val messages = mutableListOf<Message>()
        for ((i, m) in (body.arr("messages") ?: JsonArray(emptyList())).objects().withIndex()) {
            val role = m.str("role")
            if ((role == "system" || role == "developer") && messages.isEmpty()) {
                instructions += Instruction(
                    text = textOf(m["content"]),
                    role = if (role == "developer") Role.Developer else Role.System,
                    metadata = ProviderMetadata.EMPTY.withExtra(F, m, MESSAGE_KEYS),
                )
                continue
            }
            decodeMessage(m, "messages[$i]", diagnostics)?.let { messages += it }
        }
        val maxCompletion = body.int("max_completion_tokens")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, REQUEST_KEYS)
        if (maxCompletion != null) meta = meta.with(ProviderMetadata.OPENAI, MAX_TOKENS_FIELD, "max_completion_tokens")
        val toolChoice = body["tool_choice"]?.let(::decodeToolChoice)
        if (body["tool_choice"] != null && toolChoice == null) {
            meta = meta.withWireExtra(F, meta.wireExtra(F) + ("tool_choice" to body["tool_choice"]!!))
        }
        return ChatRequest(
            model = body.str("model"),
            instructions = instructions,
            messages = messages,
            tools = body.arr("tools")?.objects()?.mapNotNull { decodeTool(it, diagnostics) } ?: emptyList(),
            options = CallOptions(
                maxOutputTokens = maxCompletion ?: body.int("max_tokens"),
                temperature = body.double("temperature"),
                topP = body.double("top_p"),
                topK = body.int("top_k"),
                stop = when (val s = body["stop"]) {
                    is JsonPrimitive -> listOfNotNull(s.contentOrNull)
                    is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    else -> emptyList()
                },
                seed = body.long("seed"),
                presencePenalty = body.double("presence_penalty"),
                frequencyPenalty = body.double("frequency_penalty"),
                toolChoice = toolChoice,
                parallelToolCalls = body.bool("parallel_tool_calls"),
                responseFormat = body.obj("response_format")?.let(::decodeResponseFormat),
                reasoning = body.str("reasoning_effort")?.let { ReasoningOptions(effort = it) },
                user = body.str("user"),
                candidateCount = body.int("n"),
            ),
            stream = body.bool("stream") ?: false,
            streamUsage = body.obj("stream_options")?.bool("include_usage"),
            metadata = meta,
        )
    }

    private fun decodeMessage(m: JsonObject, path: String, d: Diagnostics): Message? {
        val extra = ProviderMetadata.EMPTY.withExtra(F, m, MESSAGE_KEYS)
        return when (val role = m.str("role")) {
            "system", "developer" -> Message(
                if (role == "developer") Role.Developer else Role.System,
                listOf(TextBlock(textOf(m["content"]))), m.str("name"), extra,
            )
            "user" -> Message(Role.User, decodeParts(m["content"], "$path.content", d), m.str("name"), extra)
            "assistant" -> {
                val blocks = mutableListOf<Block>()
                (m.str("reasoning_content") ?: m.str("reasoning"))?.takeIf { it.isNotEmpty() }?.let { blocks += ReasoningBlock(it) }
                blocks += decodeParts(m["content"], "$path.content", d)
                m.str("refusal")?.let { blocks += RefusalBlock(it) }
                m.arr("tool_calls")?.objects()?.forEach { blocks += decodeToolCall(it) }
                Message(Role.Assistant, blocks, m.str("name"), extra)
            }
            "tool" -> Message(
                Role.Tool,
                listOf(ToolResultBlock(m.str("tool_call_id") ?: "", decodeParts(m["content"], "$path.content", d))),
                metadata = extra,
            )
            else -> {
                d.warn(DiagnosticCodes.MALFORMED_INPUT, "$path.role", "unsupported role '$role' ignored")
                null
            }
        }
    }

    private fun decodeToolCall(tc: JsonObject): ToolCallBlock {
        var meta = ProviderMetadata.EMPTY.withExtra(F, tc, TOOL_CALL_KEYS)
        signatureOf(tc)?.let { meta = meta.with(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE, it) }
        otherExtraContent(tc)?.let { meta = meta.withWireExtra(F, meta.wireExtra(F) + ("extra_content" to it)) }
        val custom = tc.obj("custom")
        return if (tc.str("type") == "custom" && custom != null) {
            ToolCallBlock(tc.str("id") ?: "", custom.str("name") ?: "", custom.str("input") ?: "", ToolKind.Custom, meta)
        } else {
            val fn = tc.obj("function")
            ToolCallBlock(tc.str("id") ?: "", fn?.str("name") ?: "", fn?.str("arguments") ?: "", ToolKind.Function, meta)
        }
    }

    private fun signatureOf(tc: JsonObject): String? =
        tc.obj("extra_content")?.obj("google")?.str("thought_signature")?.takeIf { it.isNotEmpty() }

    /** extra_content 里除 Google 签名之外的部分（其他厂商字段），原样保留。 */
    private fun otherExtraContent(tc: JsonObject): JsonObject? {
        val extra = tc.obj("extra_content") ?: return null
        val google = extra.obj("google")?.let { JsonObject(it - "thought_signature") }
        val rest = if (google == null || google.isEmpty()) JsonObject(extra - "google") else JsonObject(extra + ("google" to google))
        return rest.takeIf { it.isNotEmpty() }
    }

    internal fun decodeParts(content: JsonElement?, path: String, d: Diagnostics): List<Block> = when (content) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(content.contentOrNull?.let { TextBlock(it) })
        is JsonArray -> content.objects().mapIndexedNotNull { i, p -> decodePart(p, "$path[$i]", d) }
        is JsonObject -> listOfNotNull(decodePart(content, path, d))
    }

    private fun decodePart(p: JsonObject, path: String, d: Diagnostics): Block? = when (p.str("type")) {
        "text" -> TextBlock(
            p.str("text") ?: "",
            p.arr("annotations")?.objects() ?: emptyList(),
            ProviderMetadata.EMPTY.withExtra(F, p, setOf("type", "text", "annotations")),
        )
        "refusal" -> RefusalBlock(p.str("refusal") ?: "")
        // Mistral 方言：推理以 thinking 部分出现在 content 里（docs.mistral.ai/capabilities/reasoning）
        "thinking" -> ReasoningBlock(thinkingText(p))
        "image_url" -> {
            val img = p["image_url"]
            val url = (img as? JsonObject)?.str("url") ?: (img as? JsonPrimitive)?.contentOrNull
            url?.let { ImageBlock(DataUrl.toSource(it), (img as? JsonObject)?.str("detail")) }
        }
        "input_audio" -> p.obj("input_audio")?.let { a ->
            FileBlock(MediaSource.Base64(a.str("data") ?: "", a.str("format")?.let { "audio/$it" }))
        }
        "file" -> p.obj("file")?.let { f ->
            val fileId = f.str("file_id")
            val data = f.str("file_data")
            when {
                fileId != null -> FileBlock(MediaSource.FileRef(fileId), f.str("filename"))
                data != null -> FileBlock(DataUrl.toSource(data, "application/pdf"), f.str("filename"))
                else -> null
            }
        }
        else -> {
            d.warn(DiagnosticCodes.MALFORMED_INPUT, path, "unsupported content part '${p.str("type")}' ignored")
            null
        }
    }

    private fun thinkingText(p: JsonObject): String = when (val t = p["thinking"]) {
        is JsonPrimitive -> t.contentOrNull ?: ""
        is JsonArray -> t.objects().mapNotNull { it.str("text") }.joinToString("")
        else -> ""
    }

    private fun decodeTool(t: JsonObject, d: Diagnostics): ToolSpec? {
        val meta = ProviderMetadata.EMPTY.withExtra(F, t, TOOL_KEYS)
        return when (t.str("type")) {
            "function" -> t.obj("function")?.let { fn ->
                // function 内的未知字段与 tool 外层的分开存，避免编码时摆错层级
                val inner = fn.extraFields(setOf("name", "description", "parameters", "strict"))
                FunctionTool(fn.str("name") ?: "", fn.str("description"), fn["parameters"], fn.bool("strict"),
                    if (inner.isEmpty()) meta else meta.with(ProviderMetadata.OPENAI, "functionExtra", JsonObject(inner)))
            }
            "custom" -> t.obj("custom")?.let { c -> CustomTool(c.str("name") ?: "", c.str("description"), c["format"], meta) }
            else -> HostedTool(t.str("type") ?: "", t, F)
        }
    }

    private fun decodeToolChoice(e: JsonElement): ToolChoice? = when (e) {
        is JsonPrimitive -> when (e.contentOrNull) {
            "auto" -> ToolChoice.Auto
            "none" -> ToolChoice.None
            "required" -> ToolChoice.Required
            else -> null
        }
        is JsonObject -> when (e.str("type")) {
            "function" -> e.obj("function")?.str("name")?.let { ToolChoice.Named(it) }
            "custom" -> e.obj("custom")?.str("name")?.let { ToolChoice.Named(it) }
            else -> null
        }
        else -> null
    }

    private fun decodeResponseFormat(rf: JsonObject): ResponseFormat? = when (rf.str("type")) {
        "text" -> ResponseFormat.Text
        "json_object" -> ResponseFormat.JsonObject
        "json_schema" -> rf.obj("json_schema")?.let { js ->
            ResponseFormat.JsonSchema(js.str("name") ?: "response", js["schema"] ?: JsonObject(emptyMap()), js.bool("strict"), js.str("description"))
        }
        else -> null
    }

    override fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics): JsonObject = tracking {
        val o = request.options
        put("model", request.model)
        put("messages", JsonArray(encodeMessages(request, diagnostics)))
        val tools = request.tools.mapIndexedNotNull { i, t -> encodeTool(t, "tools[$i]", diagnostics) }
        if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        o.toolChoice?.let { put("tool_choice", encodeToolChoice(it)) }
        put("parallel_tool_calls", o.parallelToolCalls)
        put("temperature", o.temperature)
        put("top_p", o.topP)
        put("top_k", o.topK)
        val maxField = request.metadata.string(ProviderMetadata.OPENAI, MAX_TOKENS_FIELD) ?: "max_tokens"
        put(maxField, o.maxOutputTokens)
        if (o.stop.isNotEmpty()) put("stop", JsonArray(o.stop.map(::JsonPrimitive)))
        put("seed", o.seed)
        put("presence_penalty", o.presencePenalty)
        put("frequency_penalty", o.frequencyPenalty)
        o.responseFormat?.let { put("response_format", encodeResponseFormat(it)) }
        o.reasoning?.let { r ->
            val effort = r.effort ?: when {
                r.mode == ReasoningMode.Disabled -> null
                r.budgetTokens != null -> Reasoning.effortForBudget(r.budgetTokens)
                r.mode == ReasoningMode.Adaptive || r.mode == ReasoningMode.Enabled -> "medium"
                else -> null
            }
            put("reasoning_effort", effort)
        }
        put("user", o.user)
        put("n", o.candidateCount)
        if (request.stream) {
            put("stream", true)
            request.streamUsage?.let { put("stream_options", buildJsonObject { put("include_usage", it) }) }
        }
        putExtras(request.metadata, F)
    }

    private fun encodeMessages(request: ChatRequest, d: Diagnostics): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        request.instructions.forEachIndexed { i, ins ->
            if (ins.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
                d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, "instructions[$i]", "cache_control has no OpenAI Chat equivalent")
            }
            out += tracking {
                put("role", if (ins.role == Role.Developer) "developer" else "system")
                put("content", ins.text)
                putExtras(ins.metadata, F)
            }
        }
        request.messages.forEachIndexed { i, m -> out += encodeMessage(m, "messages[$i]", d) }
        return out
    }

    private fun encodeMessage(m: Message, path: String, d: Diagnostics): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        when (m.role) {
            Role.System, Role.Developer -> out += tracking {
                put("role", if (m.role == Role.Developer) "developer" else "system")
                put("content", m.content.plainText("\n"))
                put("name", m.name)
                putExtras(m.metadata, F)
            }
            Role.Tool, Role.User -> {
                // 工具结果必须紧跟在带 tool_calls 的 assistant 之后：先出 tool 消息，其余内容随后
                val results = m.content.filterIsInstance<ToolResultBlock>()
                val trailingMedia = mutableListOf<Block>()
                results.forEachIndexed { i, r -> out += encodeToolResult(r, m, "$path.content[$i]", d, trailingMedia) }
                val rest = m.content.filter { it !is ToolResultBlock }
                if (trailingMedia.isNotEmpty() || rest.isNotEmpty()) {
                    out += tracking {
                        put("role", "user")
                        put("content", encodeUserContent(trailingMedia + rest, path, d))
                        put("name", m.name)
                        if (results.isEmpty()) putExtras(m.metadata, F)
                    }
                }
            }
            Role.Assistant -> out += encodeAssistant(m, path, d)
        }
        return out
    }

    private fun encodeToolResult(r: ToolResultBlock, m: Message, path: String, d: Diagnostics, trailingMedia: MutableList<Block>): JsonObject {
        val texts = mutableListOf<TextBlock>()
        for (b in r.content) when (b) {
            is TextBlock -> texts += b
            is ImageBlock, is FileBlock -> trailingMedia += b
            else -> d.warn(DiagnosticCodes.TOOL_RESULT_CONTENT_FLATTENED, path, "tool result block ${b::class.simpleName} dropped")
        }
        if (trailingMedia.isNotEmpty()) {
            d.warn(DiagnosticCodes.TOOL_RESULT_CONTENT_FLATTENED, path, "tool result media moved to a following user message")
        }
        if (r.isError) d.warn("tool_error_flag_dropped", path, "OpenAI Chat tool messages have no error flag")
        return tracking {
            put("role", "tool")
            put("tool_call_id", r.callId)
            put("content", if (texts.size <= 1 && texts.all { it.metadata.isEmpty() }) JsonPrimitive(texts.firstOrNull()?.text ?: "")
                else JsonArray(texts.map { textPart(it) }))
            if (m.role == Role.Tool) putExtras(m.metadata, F)
        }
    }

    /**
     * 纯文本（无元数据）写成字符串：不少兼容服务不接受数组形式的 content。
     * 用户的多段文本是各自独立的段落，以换行连接；助手的多段文本是同一段输出被工具调用等切开，
     * 原样拼接——与流式转换（增量直接相连）结果一致。
     */
    private fun plainTextOnly(blocks: List<Block>, separator: String = "\n"): String? =
        if (blocks.isNotEmpty() && blocks.all { it is TextBlock && it.metadata.isEmpty() && it.citations.isEmpty() })
            blocks.joinToString(separator) { (it as TextBlock).text } else null

    private fun encodeUserContent(blocks: List<Block>, path: String, d: Diagnostics): JsonElement {
        plainTextOnly(blocks)?.let { return JsonPrimitive(it) }
        return JsonArray(blocks.mapIndexedNotNull { i, b -> encodePart(b, "$path.content[$i]", d) })
    }

    private fun textPart(t: TextBlock): JsonObject = tracking {
        put("type", "text"); put("text", t.text)
        if (t.citations.isNotEmpty()) put("annotations", JsonArray(t.citations))
        putExtras(t.metadata, F)
    }

    private fun encodePart(b: Block, path: String, d: Diagnostics): JsonObject? {
        if (b.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
            d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, path, "cache_control has no OpenAI Chat equivalent")
        }
        return when (b) {
            is TextBlock -> textPart(b)
            is ImageBlock -> when (val s = b.source) {
                is MediaSource.Url -> imagePart(s.url, b.detail)
                is MediaSource.Base64 -> imagePart(DataUrl.of(s), b.detail)
                is MediaSource.FileRef -> unsupported(d, path, "image file references are not supported by OpenAI Chat")
            }
            is FileBlock -> {
                val type = b.source.mediaType ?: ""
                val s = b.source
                when {
                    type.startsWith("audio/") && s is MediaSource.Base64 -> buildJsonObject {
                        put("type", "input_audio")
                        put("input_audio", buildJsonObject { put("data", s.data); put("format", type.removePrefix("audio/")) })
                    }
                    s is MediaSource.FileRef -> buildJsonObject {
                        put("type", "file"); put("file", buildJsonObject { put("file_id", s.fileId); b.filename?.let { put("filename", it) } })
                    }
                    s is MediaSource.Base64 -> buildJsonObject {
                        put("type", "file")
                        put("file", buildJsonObject { put("file_data", DataUrl.of(s)); put("filename", b.filename ?: "file") })
                    }
                    else -> unsupported(d, path, "file URL inputs are not supported by OpenAI Chat")
                }
            }
            is RefusalBlock -> buildJsonObject { put("type", "text"); put("text", b.text) }
            is RawBlock, is HostedToolBlock -> {
                d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, path, "${b::class.simpleName} has no OpenAI Chat equivalent")
                null
            }
            else -> {
                d.warn(DiagnosticCodes.MALFORMED_INPUT, path, "${b::class.simpleName} cannot appear in user content")
                null
            }
        }
    }

    private fun unsupported(d: Diagnostics, path: String, message: String): JsonObject? {
        d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, message)
        return null
    }

    private fun imagePart(url: String, detail: String?): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", url); detail?.let { put("detail", it) } })
    }

    private fun encodeAssistant(m: Message, path: String, d: Diagnostics, forResponse: Boolean = false): JsonObject {
        val texts = mutableListOf<TextBlock>()
        val reasoning = StringBuilder()
        val refusals = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        m.content.forEachIndexed { i, b ->
            val p = "$path.content[$i]"
            when (b) {
                is TextBlock -> texts += b
                is ReasoningBlock -> {
                    if (forResponse) {
                        // 给客户端看的推理：带签名的也只取文本；加密/脱敏的没有可读文本
                        if (!b.redacted) reasoning.append(b.text.ifEmpty { b.summary.joinToString("\n") })
                    } else if (b.signature != null || b.redacted || b.encryptedContent != null) {
                        d.warn(DiagnosticCodes.REASONING_DROPPED, p, "signed or encrypted reasoning cannot be sent to OpenAI Chat")
                    } else reasoning.append(b.text)
                }
                is RefusalBlock -> refusals += b.text
                is ToolCallBlock -> toolCalls += encodeToolCall(b, toolCalls.size, includeIndex = false)
                is HostedToolBlock -> d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, p, "hosted tool activity '${b.type}' has no OpenAI Chat equivalent")
                is RawBlock -> d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, p, "'${b.type}' block from ${b.sourceFormat.id} has no OpenAI Chat equivalent")
                else -> d.warn(DiagnosticCodes.MALFORMED_INPUT, p, "${b::class.simpleName} cannot appear in assistant content")
            }
        }
        return tracking {
            put("role", "assistant")
            put("content", when {
                texts.isEmpty() -> JsonNull
                plainTextOnly(texts, separator = "") != null -> JsonPrimitive(plainTextOnly(texts, separator = "")!!)
                else -> JsonArray(texts.map(::textPart))
            })
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
            if (refusals.isNotEmpty()) put("refusal", refusals.joinToString("\n"))
            if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
            put("name", m.name)
            putExtras(m.metadata, F)
        }
    }

    internal fun encodeToolCall(b: ToolCallBlock, index: Int, includeIndex: Boolean): JsonObject = tracking {
        if (includeIndex) put("index", index)
        put("id", b.id)
        if (b.kind == ToolKind.Custom) {
            put("type", "custom")
            put("custom", buildJsonObject { put("name", b.name); put("input", b.arguments) })
        } else {
            put("type", "function")
            put("function", buildJsonObject { put("name", b.name); put("arguments", b.arguments) })
        }
        extraContent(b.metadata)?.let { put("extra_content", it) }
        putExtras(b.metadata, F)
    }

    /** Gemini 签名 → 官方 `extra_content.google.thought_signature`，与保留下来的其他 extra_content 合并。 */
    private fun extraContent(meta: ProviderMetadata): JsonObject? {
        val sig = meta.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE)
        val kept = meta.wireExtra(F)["extra_content"] as? JsonObject
        if (sig == null) return kept
        val base = kept ?: JsonObject(emptyMap())
        val google = (base.obj("google") ?: JsonObject(emptyMap())) + ("thought_signature" to JsonPrimitive(sig))
        return JsonObject(base + ("google" to JsonObject(google)))
    }

    private fun encodeTool(t: ToolSpec, path: String, d: Diagnostics): JsonObject? {
        if (t.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
            d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, path, "cache_control has no OpenAI Chat equivalent")
        }
        return when (t) {
            is FunctionTool -> tracking {
                put("type", "function")
                put("function", tracking {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
                    put("strict", t.strict)
                    (t.metadata[ProviderMetadata.OPENAI, "functionExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
                })
                putExtras(t.metadata, F)
            }
            is CustomTool -> buildJsonObject {
                put("type", "custom")
                put("custom", buildJsonObject {
                    put("name", t.name)
                    t.description?.let { put("description", it) }
                    t.format?.let { put("format", it) }
                })
            }
            is HostedTool -> if (t.sourceFormat == F) t.raw else {
                d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool '${t.type}' has no OpenAI Chat equivalent")
                null
            }
        }
    }

    private fun encodeToolChoice(c: ToolChoice): JsonElement = when (c) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", c.name) })
        }
    }

    private fun encodeResponseFormat(rf: ResponseFormat): JsonObject = when (rf) {
        ResponseFormat.Text -> buildJsonObject { put("type", "text") }
        ResponseFormat.JsonObject -> buildJsonObject { put("type", "json_object") }
        is ResponseFormat.JsonSchema -> buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject {
                put("name", rf.name)
                rf.description?.let { put("description", it) }
                put("schema", rf.schema)
                rf.strict?.let { put("strict", it) }
            })
        }
    }

    private fun textOf(e: JsonElement?): String = when (e) {
        null, is JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonArray -> e.objects().mapNotNull { it.str("text") }.joinToString("")
        is JsonObject -> e.str("text") ?: ""
    }

    // ───────────────────────── 响应 ─────────────────────────

    override fun decodeResponse(body: JsonObject, diagnostics: Diagnostics): ChatResponse {
        val choices = body.arr("choices")?.objects() ?: emptyList()
        if (choices.size > 1) diagnostics.warn(DiagnosticCodes.N_GT_1_UNSUPPORTED, "choices", "only the first choice is kept")
        val choice = choices.firstOrNull()
        val message = choice?.obj("message")
        val content = message?.let { decodeMessage(JsonObject(it + ("role" to JsonPrimitive("assistant"))), "choices[0].message", diagnostics)?.content } ?: emptyList()
        val raw = choice?.str("finish_reason")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, RESPONSE_KEYS)
        message?.let { meta = meta.with(ProviderMetadata.OPENAI, "messageExtra", JsonObject(it.extraFields(MESSAGE_KEYS)).takeIf { e -> e.isNotEmpty() }) }
        choice?.let { c -> JsonObject(c.extraFields(setOf("index", "message", "finish_reason", "delta"))).takeIf { it.isNotEmpty() }?.let { meta = meta.with(ProviderMetadata.OPENAI, "choiceExtra", it) } }
        return ChatResponse(
            id = body.str("id"),
            model = body.str("model"),
            content = content,
            finishReason = raw?.let(::finishReasonOf),
            rawFinishReason = raw,
            usage = body.obj("usage")?.let(::decodeUsage),
            created = body.long("created"),
            metadata = meta,
        )
    }

    override fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics): JsonObject = tracking {
        put("id", response.id ?: "chatcmpl-neton")
        put("object", "chat.completion")
        put("created", response.created ?: nowEpochSeconds())
        put("model", response.model ?: "")
        val message = encodeAssistant(Message(Role.Assistant, response.content), "content", diagnostics, forResponse = true).let { msg ->
            val extra = response.metadata[ProviderMetadata.OPENAI, "messageExtra"] as? JsonObject
            if (extra == null) msg else JsonObject(extra + msg)
        }
        put("choices", buildJsonArray {
            add(tracking {
                put("index", 0)
                put("message", message)
                put("finish_reason", finishReasonString(response.finishReason, response.rawFinishReason))
                (response.metadata[ProviderMetadata.OPENAI, "choiceExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
            })
        })
        response.usage?.let { put("usage", encodeUsage(it)) }
        putExtras(response.metadata, F)
    }

    internal fun finishReasonOf(raw: String): FinishReason = when (raw) {
        "stop" -> FinishReason.Stop
        "length" -> FinishReason.Length
        "tool_calls", "function_call" -> FinishReason.ToolCalls
        "content_filter" -> FinishReason.ContentFilter
        else -> FinishReason.Other
    }

    internal fun finishReasonString(reason: FinishReason?, raw: String?): String? {
        if (raw != null && raw in setOf("stop", "length", "tool_calls", "content_filter", "function_call")) return raw
        return when (reason) {
            null -> null
            FinishReason.Stop, FinishReason.PauseTurn, FinishReason.Other, FinishReason.Error -> "stop"
            FinishReason.Length -> "length"
            FinishReason.ToolCalls -> "tool_calls"
            FinishReason.ContentFilter, FinishReason.Refusal -> "content_filter"
        }
    }

    internal fun decodeUsage(u: JsonObject): Usage {
        val prompt = u.obj("prompt_tokens_details")
        val completion = u.obj("completion_tokens_details")
        return Usage(
            inputTokens = u.long("prompt_tokens"),
            outputTokens = u.long("completion_tokens"),
            // DeepSeek 的 prompt_cache_hit_tokens 与 OpenAI 的 cached_tokens 同义
            cacheReadTokens = prompt?.long("cached_tokens") ?: u.long("prompt_cache_hit_tokens"),
            reasoningTokens = completion?.long("reasoning_tokens"),
            audioInputTokens = prompt?.long("audio_tokens"),
            audioOutputTokens = completion?.long("audio_tokens"),
            imageTokens = prompt?.long("image_tokens"),
            completeness = UsageCompleteness.COMPLETE,
            raw = RawUsage(F, u),
        )
    }

    /** 同协议来源时原样写回原始 usage（计费事实不变，spec §7）。 */
    internal fun encodeUsage(u: Usage): JsonObject {
        if (u.raw?.format == F) return u.raw.json
        return tracking {
            put("prompt_tokens", u.inputTokens)
            put("completion_tokens", u.outputTokens)
            if (u.inputTokens != null && u.outputTokens != null) put("total_tokens", u.inputTokens + u.outputTokens)
            if (u.cacheReadTokens != null || u.audioInputTokens != null) put("prompt_tokens_details", tracking {
                put("cached_tokens", u.cacheReadTokens)
                put("audio_tokens", u.audioInputTokens)
            })
            if (u.reasoningTokens != null || u.audioOutputTokens != null) put("completion_tokens_details", tracking {
                put("reasoning_tokens", u.reasoningTokens)
                put("audio_tokens", u.audioOutputTokens)
            })
        }
    }

    // ───────────────────────── 错误 ─────────────────────────

    override fun encodeError(error: ProtocolError): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("message", error.message)
            put("type", when (error.kind) {
                ErrorKind.InvalidRequest, ErrorKind.ContextLengthExceeded, ErrorKind.ContentFilter, ErrorKind.NotFound -> "invalid_request_error"
                ErrorKind.Unauthorized -> "authentication_error"
                ErrorKind.Forbidden -> "permission_error"
                ErrorKind.RateLimited -> "rate_limit_error"
                else -> "api_error"
            })
            put("param", JsonNull)
            put("code", error.code?.let(::JsonPrimitive) ?: when (error.kind) {
                ErrorKind.ContextLengthExceeded -> JsonPrimitive("context_length_exceeded")
                ErrorKind.RateLimited -> JsonPrimitive("rate_limit_exceeded")
                else -> JsonNull
            })
        })
    }

    override fun decodeError(status: Int, body: String): ProtocolError {
        val err = J.parseObject(body)?.obj("error")
        val code = err?.str("code") ?: err?.str("type")
        val message = err?.str("message") ?: errorMessageFallback(body, status)
        val kind = when {
            code == "context_length_exceeded" || message.contains("maximum context length", ignoreCase = true) -> ErrorKind.ContextLengthExceeded
            code == "content_filter" || code == "content_policy_violation" -> ErrorKind.ContentFilter
            else -> ProtocolError.kindForStatus(status)
        }
        return ProtocolError(kind, message, status, code, body, F)
    }

    // ───────────────────────── 流式 ─────────────────────────

    override fun streamDecoder(diagnostics: Diagnostics): StreamDecoder = ChatStreamDecoder(diagnostics)
    override fun streamEncoder(diagnostics: Diagnostics): StreamEncoder = ChatStreamEncoder(diagnostics)

    private class ChatStreamDecoder(private val d: Diagnostics) : StreamDecoder {
        private val seq = BlockSequencer()
        private var started = false
        private var finishSeen = false
        override var completed = false
            private set
        private val toolKeys = LinkedHashMap<Int, String>()
        private val toolSignatures = HashMap<Int, String>()
        private val closedTools = HashSet<Int>()

        override fun decode(event: SseEvent): List<StreamEvent> {
            val data = event.data.trim()
            if (data.isEmpty()) return emptyList()
            if (data == "[DONE]") { completed = true; return emptyList() }
            val obj = J.parseObject(data) ?: return malformed(seq, d, WireFormat.OPENAI_CHAT).also { completed = true }
            val out = mutableListOf<StreamEvent>()
            obj.obj("error")?.let { err ->
                closeTools(out)
                seq.close(out)
                out += StreamEvent.Error(OpenAiChatCodec.decodeError(0, JsonObject(mapOf("error" to err)).toString()).let {
                    it.copy(kind = if (it.kind == ErrorKind.Unknown) ErrorKind.ServerError else it.kind, status = null)
                })
                completed = true
                return out
            }
            if (!started) {
                started = true
                out += StreamEvent.MessageStart(obj.str("id"), obj.str("model"), obj.long("created"))
            }
            val choice = obj.arr("choices")?.objects()?.firstOrNull { (it.int("index") ?: 0) == 0 }
            choice?.obj("delta")?.let { delta -> decodeDelta(delta, out) }
            choice?.str("finish_reason")?.let { raw ->
                closeTools(out)
                seq.close(out)
                finishSeen = true
                out += StreamEvent.Finish(finishReasonOf(raw), raw)
            }
            obj.obj("usage")?.let { out += StreamEvent.UsageUpdate(decodeUsage(it)) }
            return out
        }

        private fun decodeDelta(delta: JsonObject, out: MutableList<StreamEvent>) {
            (delta.str("reasoning_content") ?: delta.str("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                closeTools(out)
                seq.delta(KEY_REASONING, Delta.Reasoning(it), out) { ReasoningBlock() }
            }
            when (val content = delta["content"]) {
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    closeTools(out)
                    seq.delta(KEY_TEXT, Delta.Text(it), out) { TextBlock("") }
                }
                // Mistral 方言：推理阶段 delta.content 是部件列表
                is JsonArray -> for (part in content.objects()) when (part.str("type")) {
                    "thinking" -> thinkingText(part).takeIf { it.isNotEmpty() }?.let {
                        closeTools(out)
                        seq.delta(KEY_REASONING, Delta.Reasoning(it), out) { ReasoningBlock() }
                    }
                    "text" -> part.str("text")?.takeIf { it.isNotEmpty() }?.let {
                        closeTools(out)
                        seq.delta(KEY_TEXT, Delta.Text(it), out) { TextBlock("") }
                    }
                }
                else -> {}
            }
            delta.str("refusal")?.takeIf { it.isNotEmpty() }?.let {
                closeTools(out)
                seq.delta(KEY_REFUSAL, Delta.Text(it), out) { RefusalBlock("") }
            }
            delta.arr("tool_calls")?.objects()?.forEach { tc -> decodeToolDelta(tc, out) }
        }

        /** 并行工具调用的参数可能按 index 交错到达：每个调用一个块，直到 finish_reason 才统一关闭。 */
        private fun decodeToolDelta(tc: JsonObject, out: MutableList<StreamEvent>) {
            val index = tc.int("index") ?: 0
            if (index in closedTools) {
                d.error(DiagnosticCodes.MALFORMED_INPUT, "stream.tool_calls[$index]", "tool call delta arrived after the call was closed; dropped")
                return
            }
            signatureOf(tc)?.let { toolSignatures[index] = it }
            val key = toolKeys.getOrPut(index) { "tool:$index" }
            if (!seq.isOpen(key)) {
                seq.close(KEY_TEXT, out); seq.close(KEY_REASONING, out); seq.close(KEY_REFUSAL, out)
                val isCustom = tc.str("type") == "custom"
                seq.ensure(key, out, exclusive = false) {
                    var meta = ProviderMetadata.EMPTY
                    toolSignatures[index]?.let { meta = meta.with(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE, it) }
                    ToolCallBlock(tc.str("id") ?: "", (if (isCustom) tc.obj("custom")?.str("name") else tc.obj("function")?.str("name")) ?: "", "",
                        if (isCustom) ToolKind.Custom else ToolKind.Function, meta)
                }
            }
            val args = tc.obj("function")?.str("arguments") ?: tc.obj("custom")?.str("input")
            if (!args.isNullOrEmpty()) out += StreamEvent.BlockDelta(seq.indexOf(key)!!, Delta.Arguments(args))
        }

        private fun closeTools(out: MutableList<StreamEvent>) {
            for ((index, key) in toolKeys) {
                if (!seq.isOpen(key)) continue
                val meta = toolSignatures[index]?.let { ProviderMetadata.EMPTY.with(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE, it) }
                    ?: ProviderMetadata.EMPTY
                seq.close(key, out, meta)
                closedTools += index
            }
        }

        override fun finish(): List<StreamEvent> {
            val out = mutableListOf<StreamEvent>()
            closeTools(out)
            seq.close(out)
            // 部分兼容服务不发 [DONE]，只要见过 finish_reason 就算正常结束
            if (finishSeen) completed = true
            return out
        }
    }

    private class ChatStreamEncoder(private val d: Diagnostics) : StreamEncoder {
        private var id = "chatcmpl-neton"
        private var model = ""
        private var created: Long? = null
        private var started = false
        private var done = false
        private val blockKinds = HashMap<Int, Block>()
        private val toolIndexOf = HashMap<Int, Int>()
        private var toolCount = 0
        private var finish: StreamEvent.Finish? = null
        private var usage: Usage? = null
        private var signatureWarned = false

        override fun encode(event: StreamEvent): String {
            if (done) return ""
            val sb = StringBuilder()
            when (event) {
                is StreamEvent.MessageStart -> {
                    event.id?.let { id = it }
                    event.model?.let { model = it }
                    created = event.created
                    ensureStarted(sb)
                }
                is StreamEvent.BlockStart -> {
                    ensureStarted(sb)
                    val b = event.block
                    blockKinds[event.index] = b
                    when (b) {
                        is ToolCallBlock -> {
                            val ti = toolCount++
                            toolIndexOf[event.index] = ti
                            chunk(sb, buildJsonObject {
                                put("tool_calls", buildJsonArray { add(encodeToolCall(b, ti, includeIndex = true)) })
                            })
                        }
                        is TextBlock -> if (b.text.isNotEmpty()) chunk(sb, buildJsonObject { put("content", b.text) })
                        is ReasoningBlock -> if (b.text.isNotEmpty()) chunk(sb, buildJsonObject { put("reasoning_content", b.text) })
                        is RefusalBlock -> if (b.text.isNotEmpty()) chunk(sb, buildJsonObject { put("refusal", b.text) })
                        is HostedToolBlock -> d.warn(DiagnosticCodes.HOSTED_TOOL_OMITTED, "stream", "hosted tool activity '${b.type}' omitted")
                        else -> {}
                    }
                }
                is StreamEvent.BlockDelta -> {
                    val kind = blockKinds[event.index]
                    when (val delta = event.delta) {
                        is Delta.Text -> chunk(sb, buildJsonObject { put(if (kind is RefusalBlock) "refusal" else "content", delta.text) })
                        is Delta.Reasoning -> chunk(sb, buildJsonObject { put("reasoning_content", delta.text) })
                        is Delta.ReasoningSummary -> chunk(sb, buildJsonObject { put("reasoning_content", delta.text) })
                        is Delta.Arguments -> toolIndexOf[event.index]?.let { ti ->
                            chunk(sb, buildJsonObject {
                                put("tool_calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("index", ti)
                                        val custom = (kind as? ToolCallBlock)?.kind == ToolKind.Custom
                                        if (custom) put("custom", buildJsonObject { put("input", delta.json) })
                                        else put("function", buildJsonObject { put("arguments", delta.json) })
                                    })
                                })
                            })
                        }
                        is Delta.Signature -> if (!signatureWarned) {
                            signatureWarned = true
                            d.warn(DiagnosticCodes.SIGNATURE_DROPPED, "stream", "reasoning signatures have no OpenAI Chat equivalent")
                        }
                        is Delta.Citation -> d.warn(DiagnosticCodes.CITATIONS_DROPPED, "stream", "streamed citations omitted")
                    }
                }
                is StreamEvent.BlockStop -> {
                    val b = blockKinds[event.index]
                    val sig = event.metadata.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE)
                    if (b is ToolCallBlock && sig != null && b.metadata.string(ProviderMetadata.GEMINI, ProviderMetadata.THOUGHT_SIGNATURE) == null) {
                        val ti = toolIndexOf[event.index]!!
                        chunk(sb, buildJsonObject {
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", ti)
                                    put("extra_content", buildJsonObject { put("google", buildJsonObject { put("thought_signature", sig) }) })
                                })
                            })
                        })
                    }
                }
                is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(event.usage) ?: event.usage
                is StreamEvent.Finish -> finish = event
                is StreamEvent.Error -> {
                    ensureStarted(sb)
                    sb.append(SseWriter.data(encodeError(event.error).toString()))
                    done = true
                }
            }
            return sb.toString()
        }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            chunk(sb, buildJsonObject { put("role", "assistant"); put("content", "") })
        }

        private fun chunk(sb: StringBuilder, delta: JsonObject, finishReason: String? = null, usage: Usage? = null) {
            sb.append(SseWriter.data(buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created ?: nowEpochSeconds().also { created = it })
                put("model", model)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        put("finish_reason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
                    })
                })
                usage?.let { put("usage", encodeUsage(it)) }
            }.toString()))
        }

        override fun finish(truncated: Boolean): String {
            if (done) return ""
            done = true
            if (truncated) return ""  // 不伪造正常结束：客户端看不到 [DONE] 即知被截断
            val sb = StringBuilder()
            ensureStarted(sb)
            val f = finish
            chunk(sb, JsonObject(emptyMap()), finishReasonString(f?.reason ?: FinishReason.Stop, f?.raw) ?: "stop", usage)
            sb.append(SseWriter.data("[DONE]"))
            return sb.toString()
        }
    }

    private const val KEY_TEXT = "text"
    private const val KEY_REASONING = "reasoning"
    private const val KEY_REFUSAL = "refusal"
}

/** 推理强度与预算的互换（主流约定；目标协议需要预算而只有强度时使用）。 */
object Reasoning {
    /** 推理档位占输出上限的比例（主流 SDK 的约定）。 */
    private val SHARE = mapOf("minimal" to 0.02, "low" to 0.1, "medium" to 0.3, "high" to 0.6, "xhigh" to 0.9, "max" to 0.95)
    const val MIN_BUDGET = 1024

    /**
     * 强度 → 预算。已知输出上限时按比例换算（不低于 [MIN_BUDGET]），预算总在上限之内、留出正文空间；
     * 不知道上限时用固定档位。
     */
    fun budgetForEffort(effort: String?, maxOutputTokens: Int?): Int? {
        val share = SHARE[effort] ?: return null
        if (maxOutputTokens == null) return budgetForEffort(effort)
        return maxOf(MIN_BUDGET, (maxOutputTokens * share).toInt())
    }

    fun budgetForEffort(effort: String?): Int? = when (effort) {
        "minimal" -> 512
        "low" -> 1024
        "medium" -> 8192
        "high" -> 16384
        "xhigh", "max" -> 32768
        else -> null
    }

    fun effortForBudget(budget: Int?): String? = when {
        budget == null -> null
        budget <= 0 -> "minimal"
        budget <= 2048 -> "low"
        budget <= 8192 -> "medium"
        else -> "high"
    }
}
