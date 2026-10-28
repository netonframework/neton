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
 * Anthropic Messages。
 *
 * 块顺序原样保留（thinking → text → tool_use 交错都可以）；thinking 签名、redacted thinking、
 * `cache_control`（含 TTL）、托管工具块都能写回本协议（spec §4.4）。
 */
@neton.ai.ExperimentalNetonAiApi
object AnthropicCodec : ProtocolCodec {
    override val format = WireFormat.ANTHROPIC
    private val F = WireFormat.ANTHROPIC

    /** Anthropic 要求 max_tokens；调用方没给时用这个值。 */
    const val DEFAULT_MAX_TOKENS = 4096
    /** thinking 的最小预算（官方下限）。 */
    const val MIN_THINKING_BUDGET = 1024

    private val REQUEST_KEYS = setOf(
        "model", "messages", "system", "max_tokens", "temperature", "top_p", "top_k", "stop_sequences",
        "stream", "tools", "tool_choice", "thinking", "metadata", "output_config", "output_format",
    )
    private val CC = ProviderMetadata.CACHE_CONTROL
    private const val EFFORT_IN_OUTPUT_CONFIG = "effortInOutputConfig"
    private val NS = ProviderMetadata.ANTHROPIC

    // ───────────────────────── 请求 ─────────────────────────

    override fun decodeRequest(body: JsonObject, diagnostics: Diagnostics): ChatRequest {
        val instructions = when (val sys = body["system"]) {
            is JsonPrimitive -> listOfNotNull(sys.contentOrNull?.let { Instruction(it) })
            is JsonArray -> sys.objects().map { b ->
                Instruction(b.str("text") ?: "", Role.System, cacheMeta(b).withExtra(F, b, setOf("type", "text", "cache_control")))
            }
            else -> emptyList()
        }
        val messages = (body.arr("messages") ?: JsonArray(emptyList())).objects().mapIndexed { i, m ->
            // Claude Code 会在对话中途发 role=system 的消息，按 system 保留
            val role = when (m.str("role")) { "assistant" -> Role.Assistant; "system" -> Role.System; else -> Role.User }
            Message(role, decodeContent(m["content"], "messages[$i].content", diagnostics),
                metadata = ProviderMetadata.EMPTY.withExtra(F, m, setOf("role", "content")))
        }
        val tc = body.obj("tool_choice")
        val thinking = body.obj("thinking")
        val metadataObj = body.obj("metadata")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, REQUEST_KEYS)
        metadataObj?.let { m -> JsonObject(m - "user_id").takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "metadataExtra", it) } }
        val outputConfig = body.obj("output_config")
        val effortHint = outputConfig?.str("effort")
        if (effortHint != null) meta = meta.with(NS, EFFORT_IN_OUTPUT_CONFIG, JsonPrimitive(true))
        outputConfig?.let { oc -> JsonObject(oc - "effort" - "format").takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "outputConfigExtra", it) } }
        // 结构化输出：output_config.format（GA）；output_format 是已废弃的 beta 写法，读入后按新写法写回
        val format = outputConfig?.obj("format") ?: body.obj("output_format")
        return ChatRequest(
            model = body.str("model"),
            instructions = instructions,
            messages = messages,
            tools = body.arr("tools")?.objects()?.map(::decodeTool) ?: emptyList(),
            options = CallOptions(
                maxOutputTokens = body.int("max_tokens"),
                temperature = body.double("temperature"),
                topP = body.double("top_p"),
                topK = body.int("top_k"),
                stop = body.arr("stop_sequences")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                toolChoice = tc?.let(::decodeToolChoice),
                parallelToolCalls = if (tc?.bool("disable_parallel_tool_use") == true) false else null,
                reasoning = decodeThinking(thinking, effortHint),
                user = metadataObj?.str("user_id"),
                responseFormat = format?.takeIf { it.str("type") == "json_schema" }?.let {
                    ResponseFormat.JsonSchema("response", it["schema"] ?: JsonObject(emptyMap()))
                },
            ),
            stream = body.bool("stream") ?: false,
            metadata = meta,
        )
    }

    private fun decodeThinking(thinking: JsonObject?, effort: String?): ReasoningOptions? {
        val mode = when (thinking?.str("type")) {
            "enabled" -> ReasoningMode.Enabled
            "adaptive" -> ReasoningMode.Adaptive
            "disabled" -> ReasoningMode.Disabled
            else -> null
        }
        if (mode == null && effort == null) return null
        return ReasoningOptions(mode = mode, effort = effort, budgetTokens = thinking?.int("budget_tokens"))
    }

    private fun cacheMeta(b: JsonObject): ProviderMetadata =
        b["cache_control"]?.let { ProviderMetadata.EMPTY.with(NS, CC, it) } ?: ProviderMetadata.EMPTY

    internal fun decodeContent(content: JsonElement?, path: String, d: Diagnostics): List<Block> = when (content) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(content.contentOrNull?.let { TextBlock(it) })
        is JsonArray -> content.objects().mapIndexed { i, b -> decodeBlock(b, "$path[$i]", d) }
        is JsonObject -> listOf(decodeBlock(content, path, d))
    }

    private fun decodeBlock(b: JsonObject, path: String, d: Diagnostics): Block {
        val cache = cacheMeta(b)
        fun meta(known: Set<String>) = cache.withExtra(F, b, known + setOf("type", "cache_control"))
        return when (val type = b.str("type")) {
            "text" -> TextBlock(b.str("text") ?: "", b.arr("citations")?.objects() ?: emptyList(), meta(setOf("text", "citations")))
            "image" -> decodeSource(b.obj("source"))?.let { ImageBlock(it, metadata = meta(setOf("source"))) } ?: raw(b)
            "document" -> decodeSource(b.obj("source"))?.let { FileBlock(it, b.str("title"), meta(setOf("source", "title"))) } ?: raw(b)
            "tool_use" -> ToolCallBlock(b.str("id") ?: "", b.str("name") ?: "", (b["input"] ?: JsonObject(emptyMap())).toString(),
                ToolKind.Function, meta(setOf("id", "name", "input")))
            "tool_result" -> ToolResultBlock(
                callId = b.str("tool_use_id") ?: "",
                content = decodeContent(b["content"], "$path.content", d),
                isError = b.bool("is_error") ?: false,
                metadata = meta(setOf("tool_use_id", "content", "is_error")),
            )
            "thinking" -> ReasoningBlock(b.str("thinking") ?: "", signature = b.str("signature"), metadata = meta(setOf("thinking", "signature")))
            "redacted_thinking" -> ReasoningBlock(redactedData = b.str("data") ?: "", metadata = meta(setOf("data")))
            null -> raw(b)
            else -> if (isHostedBlock(type)) HostedToolBlock(type, b, F) else raw(b)
        }
    }

    private fun raw(b: JsonObject): Block = RawBlock(b.str("type") ?: "", b, F)

    private fun isHostedBlock(type: String): Boolean =
        type == "server_tool_use" || type.endsWith("_tool_result") || type == "mcp_tool_use" || type == "mcp_tool_result"

    private fun decodeSource(s: JsonObject?): MediaSource? = when (s?.str("type")) {
        "base64" -> MediaSource.Base64(s.str("data") ?: "", s.str("media_type"))
        "url" -> MediaSource.Url(s.str("url") ?: "")
        "file" -> MediaSource.FileRef(s.str("file_id") ?: "")
        else -> null
    }

    private fun decodeTool(t: JsonObject): ToolSpec {
        val type = t.str("type")
        return if (type == null || type == "custom") {
            FunctionTool(t.str("name") ?: "", t.str("description"), t["input_schema"], t.bool("strict"),
                cacheMeta(t).withExtra(F, t, setOf("type", "name", "description", "input_schema", "strict", "cache_control")))
        } else HostedTool(type, t, F)
    }

    private fun decodeToolChoice(tc: JsonObject): ToolChoice? = when (tc.str("type")) {
        "auto" -> ToolChoice.Auto
        "any" -> ToolChoice.Required
        "none" -> ToolChoice.None
        "tool" -> tc.str("name")?.let { ToolChoice.Named(it) }
        else -> null
    }

    override fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics): JsonObject = tracking {
        val o = request.options
        val d = diagnostics
        put("model", request.model)
        val thinking = encodeThinking(o.reasoning, o.maxOutputTokens)
        val budget = thinking?.int("budget_tokens")
        // 预算必须小于 max_tokens：不够时把 max_tokens 抬到「预算 + 1024」，而不是放弃推理（与主流网关一致）
        val requested = o.maxOutputTokens ?: DEFAULT_MAX_TOKENS
        val maxTokens = if (budget != null && requested <= budget) budget + MIN_THINKING_BUDGET else requested
        put("max_tokens", maxTokens)
        encodeSystem(request, d)?.let { put("system", it) }
        put("messages", JsonArray(encodeMessages(request.messages, d)))
        val tools = request.tools.mapIndexedNotNull { i, t -> encodeTool(t, "tools[$i]", d) }
        val toolsActive = tools.isNotEmpty()
        if (toolsActive) put("tools", JsonArray(tools))
        if (toolsActive || o.toolChoice == ToolChoice.None) encodeToolChoice(o.toolChoice, o.parallelToolCalls)?.let { put("tool_choice", it) }
        val thinkingOn = thinking?.str("type").let { it == "enabled" || it == "adaptive" }
        if (thinkingOn && (o.temperature != null || o.topK != null)) {
            // 开启 thinking 时 Anthropic 不接受修改 temperature / top_k
            d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "temperature", "temperature/top_k are not sent while thinking is enabled")
        }
        if (!thinkingOn) put("temperature", o.temperature)
        put("top_p", o.topP)
        if (!thinkingOn) put("top_k", o.topK)
        if (o.stop.isNotEmpty()) put("stop_sequences", JsonArray(o.stop.map(::JsonPrimitive)))
        thinking?.let { put("thinking", it) }
        encodeOutputConfig(request, d)?.let { put("output_config", it) }
        val metaExtra = request.metadata[NS, "metadataExtra"] as? JsonObject
        if (o.user != null || metaExtra != null) put("metadata", JsonObject((metaExtra ?: JsonObject(emptyMap())) + listOfNotNull(o.user?.let { "user_id" to JsonPrimitive(it) })))
        if (request.stream) put("stream", true)
        if (o.seed != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "seed", "Anthropic has no seed parameter")
        if (o.presencePenalty != null || o.frequencyPenalty != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "penalty", "Anthropic has no presence/frequency penalty")
        if ((o.candidateCount ?: 1) > 1) d.error(DiagnosticCodes.N_GT_1_UNSUPPORTED, "n", "Anthropic returns a single candidate")
        putExtras(request.metadata, F)
    }

    /**
     * `output_config`：推理强度（自适应 thinking 或源请求本来就写在这里）与结构化输出
     * （platform.claude.com/docs/en/build-with-claude/structured-outputs：`format: {type: json_schema, schema}`，无需 beta 头）。
     */
    private fun encodeOutputConfig(request: ChatRequest, d: Diagnostics): JsonObject? {
        val o = request.options
        val r = o.reasoning
        return tracking {
            if (r?.effort != null && (r.mode == ReasoningMode.Adaptive || request.metadata[NS, EFFORT_IN_OUTPUT_CONFIG] != null)) put("effort", r.effort)
            when (val rf = o.responseFormat) {
                is ResponseFormat.JsonSchema -> put("format", buildJsonObject { put("type", "json_schema"); put("schema", rf.schema) })
                ResponseFormat.JsonObject -> d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "response_format",
                    "Anthropic has no schema-less JSON mode; the request is sent without a format constraint")
                else -> {}
            }
            (request.metadata[NS, "outputConfigExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
        }.takeIf { it.isNotEmpty() }
    }

    private fun encodeSystem(request: ChatRequest, d: Diagnostics): JsonElement? {
        val ins = request.instructions
        if (ins.isEmpty()) return null
        if (ins.size == 1 && ins[0].metadata.isEmpty()) return JsonPrimitive(ins[0].text)
        return JsonArray(ins.map { i ->
            tracking {
                put("type", "text"); put("text", i.text)
                i.metadata[NS, CC]?.let { put("cache_control", it) }
                putExtras(i.metadata, F)
            }
        })
    }

    private fun encodeMessages(messages: List<Message>, d: Diagnostics): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        // Anthropic 只有 user/assistant：tool 消息并入 user，相邻同角色消息合并（API 要求交替）
        var pendingRole: String? = null
        val pending = mutableListOf<JsonElement>()
        var pendingMeta: ProviderMetadata = ProviderMetadata.EMPTY
        fun flush() {
            val role = pendingRole ?: return
            out += tracking {
                put("role", role)
                put("content", contentElement(pending.toList()))
                putExtras(pendingMeta, F)
            }
            pending.clear(); pendingRole = null; pendingMeta = ProviderMetadata.EMPTY
        }
        messages.forEachIndexed { i, m ->
            val path = "messages[$i]"
            val role = when (m.role) {
                Role.Assistant -> "assistant"
                Role.User, Role.Tool -> "user"
                Role.System, Role.Developer -> {
                    d.warn(DiagnosticCodes.INSTRUCTION_MOVED, path, "mid-conversation system message sent as a user message")
                    "user"
                }
            }
            val blocks = if (m.role == Role.System || m.role == Role.Developer) {
                listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("[system] " + m.content.plainText("\n")))))
            } else m.content.mapIndexedNotNull { j, b -> encodeBlock(b, "$path.content[$j]", d, m.role) }
            if (blocks.isEmpty()) return@forEachIndexed
            if (role != pendingRole) { flush(); pendingRole = role; pendingMeta = m.metadata } else pendingMeta = ProviderMetadata.EMPTY
            // 同一条 user 消息里 tool_result 必须排在最前面
            if (role == "user") {
                val (results, others) = blocks.partition { (it as? JsonObject)?.str("type") == "tool_result" }
                val firstNonResult = pending.indexOfFirst { (it as? JsonObject)?.str("type") != "tool_result" }
                if (results.isNotEmpty() && firstNonResult >= 0) pending.addAll(firstNonResult, results) else pending.addAll(results)
                pending.addAll(others)
            } else pending.addAll(blocks)
        }
        flush()
        return out
    }

    /** 只有一个无元数据的 text 块时写成字符串，与常见客户端的写法一致。 */
    private fun contentElement(blocks: List<JsonElement>): JsonElement {
        val only = blocks.singleOrNull() as? JsonObject
        if (only != null && only.keys == setOf("type", "text") && only.str("type") == "text") return only["text"]!!
        return JsonArray(blocks)
    }

    private fun encodeBlock(b: Block, path: String, d: Diagnostics, role: Role, forResponse: Boolean = false): JsonObject? {
        val cache = b.metadata[NS, CC]
        return when (b) {
            is TextBlock -> tracking {
                put("type", "text"); put("text", b.text)
                if (b.citations.isNotEmpty()) {
                    // 只有 Anthropic 形状的引用才能写回
                    if (b.citations.all { it.str("type")?.let { t -> t.endsWith("_location") || t == "web_search_result_location" || t == "search_result_location" } == true }) {
                        put("citations", JsonArray(b.citations))
                    } else d.warn(DiagnosticCodes.CITATIONS_DROPPED, path, "citations from another protocol dropped")
                }
                put("cache_control", cache); putExtras(b.metadata, F)
            }
            is ImageBlock -> tracking {
                put("type", "image"); put("source", encodeSource(b.source, path, d) ?: return null)
                put("cache_control", cache); putExtras(b.metadata, F)
            }
            is FileBlock -> {
                if (b.source.mediaType?.startsWith("audio/") == true || b.source.mediaType?.startsWith("video/") == true) {
                    d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, "Anthropic does not accept ${b.source.mediaType}")
                    return null
                }
                tracking {
                    put("type", "document"); put("source", encodeSource(b.source, path, d) ?: return null)
                    put("title", b.filename)
                    put("cache_control", cache); putExtras(b.metadata, F)
                }
            }
            is ToolCallBlock -> tracking {
                if (b.kind == ToolKind.Custom) d.warn(DiagnosticCodes.CUSTOM_TOOL_AS_FUNCTION, path, "custom tool call sent as tool_use with {input}")
                put("type", "tool_use"); put("id", b.id); put("name", b.name)
                put("input", if (b.kind == ToolKind.Custom) buildJsonObject { put("input", b.arguments) } else argumentsObject(b.arguments, path, d))
                put("cache_control", cache); putExtras(b.metadata, F)
            }
            is ToolResultBlock -> tracking {
                put("type", "tool_result"); put("tool_use_id", b.callId)
                val inner = b.content.mapIndexedNotNull { j, c -> encodeBlock(c, "$path.content[$j]", d, Role.User) }
                put("content", contentElement(inner))
                if (b.isError) put("is_error", true)
                put("cache_control", cache); putExtras(b.metadata, F)
            }
            is ReasoningBlock -> when {
                role != Role.Assistant -> null
                b.redactedData != null -> tracking { put("type", "redacted_thinking"); put("data", b.redactedData); putExtras(b.metadata, F) }
                b.signature != null -> tracking {
                    put("type", "thinking"); put("thinking", b.text); put("signature", b.signature); putExtras(b.metadata, F)
                }
                forResponse -> if (b.text.isEmpty() && b.summary.isEmpty()) null else buildJsonObject {
                    // 给客户端的响应：其他协议的推理按 thinking 展示，签名留空（与主流网关一致）
                    put("type", "thinking"); put("thinking", b.text.ifEmpty { b.summary.joinToString("\n") }); put("signature", "")
                }
                else -> {
                    // 没有签名的推理（来自其他协议）发给 Anthropic 会被拒绝，只能丢弃
                    d.warn(DiagnosticCodes.REASONING_DROPPED, path, "unsigned reasoning cannot be sent to Anthropic")
                    null
                }
            }
            is RefusalBlock -> {
                d.warn(DiagnosticCodes.REFUSAL_AS_TEXT, path, "refusal sent as text")
                buildJsonObject { put("type", "text"); put("text", b.text) }
            }
            is HostedToolBlock -> if (b.sourceFormat == F) b.raw else {
                d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool activity '${b.type}' from ${b.sourceFormat.id} dropped")
                null
            }
            is RawBlock -> if (b.sourceFormat == F) b.raw else {
                d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, path, "'${b.type}' block from ${b.sourceFormat.id} dropped")
                null
            }
        }
    }

    private fun argumentsObject(arguments: String, path: String, d: Diagnostics): JsonElement {
        if (arguments.isBlank()) return JsonObject(emptyMap())
        val parsed = runCatching { J.json.parseToJsonElement(arguments) }.getOrNull()
        if (parsed is JsonObject) return parsed
        d.error(DiagnosticCodes.MALFORMED_INPUT, path, "tool arguments are not a JSON object")
        return JsonObject(emptyMap())
    }

    private fun encodeSource(s: MediaSource, path: String, d: Diagnostics): JsonObject? = when (s) {
        is MediaSource.Base64 -> buildJsonObject { put("type", "base64"); put("media_type", s.mediaType ?: "application/octet-stream"); put("data", s.data) }
        is MediaSource.Url -> buildJsonObject { put("type", "url"); put("url", s.url) }
        is MediaSource.FileRef -> if (s.fileId.startsWith("file_")) buildJsonObject { put("type", "file"); put("file_id", s.fileId) } else {
            d.error(DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE, path, "file reference '${s.fileId}' is not an Anthropic file id")
            null
        }
    }

    private fun encodeTool(t: ToolSpec, path: String, d: Diagnostics): JsonObject? = when (t) {
        is FunctionTool -> tracking {
            put("name", t.name)
            put("description", t.description)
            put("input_schema", t.parameters ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
            put("strict", t.strict)
            put("cache_control", t.metadata[NS, CC])
            putExtras(t.metadata, F)
        }
        is CustomTool -> {
            d.warn(DiagnosticCodes.CUSTOM_TOOL_AS_FUNCTION, path, "custom tool sent as a function with a single string 'input'")
            buildJsonObject {
                put("name", t.name)
                t.description?.let { put("description", it) }
                put("input_schema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("input", buildJsonObject { put("type", "string") }) })
                    put("required", buildJsonArray { add(JsonPrimitive("input")) })
                })
            }
        }
        is HostedTool -> if (t.sourceFormat == F) t.raw else {
            d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool '${t.type}' from ${t.sourceFormat.id} has no Anthropic equivalent")
            null
        }
    }

    private fun encodeToolChoice(c: ToolChoice?, parallel: Boolean?): JsonObject? {
        if (c == null && parallel != false) return null
        return buildJsonObject {
            when (c) {
                null, ToolChoice.Auto -> put("type", "auto")
                ToolChoice.None -> put("type", "none")
                ToolChoice.Required -> put("type", "any")
                is ToolChoice.Named -> { put("type", "tool"); put("name", c.name) }
            }
            if (parallel == false && c != ToolChoice.None) put("disable_parallel_tool_use", true)
        }
    }

    private fun encodeThinking(r: ReasoningOptions?, maxTokens: Int?): JsonObject? {
        if (r == null) return null
        return when (r.mode) {
            ReasoningMode.Disabled -> buildJsonObject { put("type", "disabled") }
            ReasoningMode.Adaptive -> buildJsonObject { put("type", "adaptive") }
            else -> {
                if (r.mode == null && r.budgetTokens == null && (r.effort == null || r.effort == "none")) return null
                val wanted = r.budgetTokens ?: Reasoning.budgetForEffort(r.effort, maxTokens) ?: return null
                buildJsonObject { put("type", "enabled"); put("budget_tokens", maxOf(MIN_THINKING_BUDGET, wanted)) }
            }
        }
    }

    // ───────────────────────── 响应 ─────────────────────────

    override fun decodeResponse(body: JsonObject, diagnostics: Diagnostics): ChatResponse {
        val raw = body.str("stop_reason")
        return ChatResponse(
            id = body.str("id"),
            model = body.str("model"),
            content = decodeContent(body["content"], "content", diagnostics),
            finishReason = raw?.let(::finishReasonOf),
            rawFinishReason = raw,
            usage = body.obj("usage")?.let { decodeUsage(it, UsageCompleteness.COMPLETE) },
            metadata = ProviderMetadata.EMPTY.withExtra(F, body, setOf("id", "type", "role", "model", "content", "stop_reason", "usage")),
        )
    }

    override fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics): JsonObject = tracking {
        put("id", response.id ?: "msg_neton")
        put("type", "message")
        put("role", "assistant")
        put("model", response.model ?: "")
        put("content", JsonArray(response.content.mapIndexedNotNull { i, b -> encodeBlock(b, "content[$i]", diagnostics, Role.Assistant, forResponse = true) }))
        put("stop_reason", stopReasonString(response.finishReason, response.rawFinishReason) ?: "end_turn")
        if ("stop_sequence" !in response.metadata.wireExtra(F)) put("stop_sequence", JsonNull)
        put("usage", encodeUsage(response.usage ?: Usage.UNKNOWN))
        putExtras(response.metadata, F)
    }

    internal fun finishReasonOf(raw: String): FinishReason = when (raw) {
        "end_turn", "stop_sequence" -> FinishReason.Stop
        "max_tokens", "model_context_window_exceeded" -> FinishReason.Length
        "tool_use" -> FinishReason.ToolCalls
        "pause_turn" -> FinishReason.PauseTurn
        "refusal" -> FinishReason.Refusal
        else -> FinishReason.Other
    }

    private val STOP_REASONS = setOf("end_turn", "stop_sequence", "max_tokens", "model_context_window_exceeded", "tool_use", "pause_turn", "refusal")

    internal fun stopReasonString(reason: FinishReason?, raw: String?): String? {
        if (raw != null && raw in STOP_REASONS) return raw
        return when (reason) {
            null -> null
            FinishReason.Stop, FinishReason.Other, FinishReason.Error -> "end_turn"
            FinishReason.Length -> "max_tokens"
            FinishReason.ToolCalls -> "tool_use"
            FinishReason.PauseTurn -> "pause_turn"
            FinishReason.ContentFilter, FinishReason.Refusal -> "refusal"
        }
    }

    /** Anthropic 的 input_tokens 不含缓存：换算到 IR 口径时把缓存读写加回去（[Usage] 文档）。 */
    internal fun decodeUsage(u: JsonObject, completeness: UsageCompleteness): Usage {
        val read = u.long("cache_read_input_tokens")
        val creation = u.obj("cache_creation")
        val w5 = creation?.long("ephemeral_5m_input_tokens") ?: if (creation == null) u.long("cache_creation_input_tokens") else null
        val w1 = creation?.long("ephemeral_1h_input_tokens")
        val base = u.long("input_tokens")
        val totalWrite = u.long("cache_creation_input_tokens") ?: if (w5 == null && w1 == null) null else (w5 ?: 0) + (w1 ?: 0)
        return Usage(
            inputTokens = base?.let { it + (read ?: 0) + (totalWrite ?: 0) },
            outputTokens = u.long("output_tokens"),
            cacheReadTokens = read,
            cacheWrite5mTokens = w5,
            cacheWrite1hTokens = w1,
            completeness = completeness,
            raw = RawUsage(F, u),
        )
    }

    internal fun encodeUsage(u: Usage): JsonObject {
        if (u.raw?.format == F) return u.raw.json
        val write = u.cacheWriteTokens
        return tracking {
            put("input_tokens", u.inputTokens?.let { (it - (u.cacheReadTokens ?: 0) - (write ?: 0)).coerceAtLeast(0) } ?: 0)
            put("output_tokens", u.outputTokens ?: 0)
            put("cache_creation_input_tokens", write)
            put("cache_read_input_tokens", u.cacheReadTokens)
            if (u.cacheWrite5mTokens != null || u.cacheWrite1hTokens != null) put("cache_creation", buildJsonObject {
                put("ephemeral_5m_input_tokens", u.cacheWrite5mTokens ?: 0)
                put("ephemeral_1h_input_tokens", u.cacheWrite1hTokens ?: 0)
            })
        }
    }

    // ───────────────────────── 错误 ─────────────────────────

    override fun encodeError(error: ProtocolError): JsonObject = buildJsonObject {
        put("type", "error")
        put("error", buildJsonObject {
            put("type", when (error.kind) {
                ErrorKind.InvalidRequest, ErrorKind.ContextLengthExceeded, ErrorKind.ContentFilter -> "invalid_request_error"
                ErrorKind.Unauthorized -> "authentication_error"
                ErrorKind.Forbidden -> "permission_error"
                ErrorKind.NotFound -> "not_found_error"
                ErrorKind.RateLimited -> "rate_limit_error"
                ErrorKind.Overloaded -> "overloaded_error"
                ErrorKind.Timeout -> "timeout_error"
                else -> "api_error"
            })
            put("message", error.message)
        })
    }

    override fun decodeError(status: Int, body: String): ProtocolError {
        val err = J.parseObject(body)?.obj("error")
        val type = err?.str("type")
        val message = err?.str("message") ?: errorMessageFallback(body, status)
        val kind = when (type) {
            "invalid_request_error" -> if (message.contains("prompt is too long", true) || message.contains("context", true) && message.contains("exceed", true))
                ErrorKind.ContextLengthExceeded else ErrorKind.InvalidRequest
            "authentication_error" -> ErrorKind.Unauthorized
            "permission_error" -> ErrorKind.Forbidden
            "not_found_error" -> ErrorKind.NotFound
            "rate_limit_error" -> ErrorKind.RateLimited
            "overloaded_error" -> ErrorKind.Overloaded
            "timeout_error" -> ErrorKind.Timeout
            "request_too_large" -> ErrorKind.ContextLengthExceeded
            "api_error" -> ErrorKind.ServerError
            else -> ProtocolError.kindForStatus(status)
        }
        return ProtocolError(kind, message, status, type, body, F)
    }

    // ───────────────────────── 流式 ─────────────────────────

    override fun streamDecoder(diagnostics: Diagnostics): StreamDecoder = MessagesStreamDecoder(diagnostics)
    override fun streamEncoder(diagnostics: Diagnostics): StreamEncoder = MessagesStreamEncoder(diagnostics)

    private class MessagesStreamDecoder(private val d: Diagnostics) : StreamDecoder {
        private val seq = BlockSequencer()
        override var completed = false
            private set
        private var rawUsage = JsonObject(emptyMap())
        /** 当前打开的消息；为 null 表示还没开始。 */
        private var messageId: String? = null
        private var started = false
        /** 已报告流错误：之后的事件一律忽略。 */
        private var failed = false

        override fun decode(event: SseEvent): List<StreamEvent> {
            if (failed) return emptyList()
            val data = event.data.trim()
            if (data.isEmpty()) return emptyList()
            val obj = J.parseObject(data) ?: return malformed(seq, d, WireFormat.ANTHROPIC).also { completed = true; failed = true }
            val out = mutableListOf<StreamEvent>()
            // 报文里通常带 type；没有时以 SSE 的 event 名为准
            when (obj.str("type") ?: event.event) {
                "message_start" -> {
                    val m = obj.obj("message")
                    if (started) {
                        // 同一条消息重复开始：忽略；上一条还没结束又开始另一条：两条消息的块会串在一起，按畸形流报错
                        if (m?.str("id") == messageId) return out
                        return malformed(seq, d, WireFormat.ANTHROPIC).also { completed = true; failed = true }
                    }
                    started = true
                    messageId = m?.str("id")
                    out += StreamEvent.MessageStart(m?.str("id"), m?.str("model"))
                    m?.obj("usage")?.let { out += usageUpdate(it, UsageCompleteness.PARTIAL) }
                }
                "content_block_start" -> {
                    val index = obj.int("index") ?: 0
                    val cb = obj.obj("content_block") ?: JsonObject(emptyMap())
                    seq.ensure(index, out, exclusive = false) { initialBlock(cb) }
                }
                "content_block_delta" -> {
                    val index = obj.int("index") ?: 0
                    val delta = obj.obj("delta") ?: return out
                    val ir = when (delta.str("type")) {
                        "text_delta" -> Delta.Text(delta.str("text") ?: "")
                        "thinking_delta" -> Delta.Reasoning(delta.str("thinking") ?: "")
                        "signature_delta" -> Delta.Signature(delta.str("signature") ?: "")
                        "input_json_delta" -> Delta.Arguments(delta.str("partial_json") ?: "")
                        "citations_delta" -> delta.obj("citation")?.let { Delta.Citation(it) }
                        else -> null
                    } ?: return out
                    seq.delta(index, ir, out, exclusive = false) { TextBlock("") }
                }
                "content_block_stop" -> seq.close(obj.int("index") ?: 0, out)
                "message_delta" -> {
                    seq.close(out)
                    obj.obj("usage")?.let { out += usageUpdate(it, UsageCompleteness.COMPLETE) }
                    obj.obj("delta")?.str("stop_reason")?.let { out += StreamEvent.Finish(finishReasonOf(it), it) }
                }
                "message_stop" -> { seq.close(out); completed = true }
                "error" -> {
                    seq.close(out)
                    out += StreamEvent.Error(decodeError(0, obj.toString()).copy(status = null))
                    completed = true
                }
            }
            return out
        }

        private fun usageUpdate(u: JsonObject, completeness: UsageCompleteness): StreamEvent.UsageUpdate {
            rawUsage = JsonObject(rawUsage + u.filterValues { it !is JsonNull })
            return StreamEvent.UsageUpdate(decodeUsage(rawUsage, completeness))
        }

        private fun initialBlock(cb: JsonObject): Block = when (val type = cb.str("type")) {
            "text" -> TextBlock(cb.str("text") ?: "", cb.arr("citations")?.objects() ?: emptyList())
            "thinking" -> ReasoningBlock(cb.str("thinking") ?: "", signature = cb.str("signature")?.takeIf { it.isNotEmpty() })
            "redacted_thinking" -> ReasoningBlock(redactedData = cb.str("data") ?: "")
            "tool_use" -> ToolCallBlock(cb.str("id") ?: "", cb.str("name") ?: "", "")
            null -> RawBlock("", cb, WireFormat.ANTHROPIC)
            else -> if (isHostedBlock(type)) HostedToolBlock(type, cb, WireFormat.ANTHROPIC) else RawBlock(type, cb, WireFormat.ANTHROPIC)
        }

        override fun finish(): List<StreamEvent> {
            val out = mutableListOf<StreamEvent>()
            seq.close(out)
            return out
        }
    }

    private class MessagesStreamEncoder(private val d: Diagnostics) : StreamEncoder {
        private val customTools = neton.ai.protocol.stream.CustomToolCallsAsFunctions(d)
        private var id = "msg_neton"
        private var model = ""
        private var started = false
        private var done = false
        private var nextIndex = 0
        private val indexOf = HashMap<Int, Int>()
        private val blocks = HashMap<Int, Block>()
        private var openIr: Int? = null
        private val signed = HashSet<Int>()
        private var usage: Usage? = null
        private var finish: StreamEvent.Finish? = null

        private val sequential = neton.ai.protocol.stream.SequentialBlocks()

        override fun encode(event: StreamEvent): String {
            if (done) return ""
            val sb = StringBuilder()
            for (c in customTools.accept(event)) for (e in sequential.accept(c)) encodeOne(e, sb)
            return sb.toString()
        }

        private fun encodeOne(event: StreamEvent, sb: StringBuilder) {
            if (done) return
            when (event) {
                is StreamEvent.MessageStart -> {
                    event.id?.let { id = it }
                    event.model?.let { model = it }
                    ensureStarted(sb)
                }
                is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(event.usage) ?: event.usage
                is StreamEvent.BlockStart -> {
                    ensureStarted(sb)
                    closeOpen(sb)
                    val start = startBlock(event.block) ?: return
                    val idx = nextIndex++
                    indexOf[event.index] = idx
                    blocks[event.index] = event.block
                    openIr = event.index
                    emit(sb, "content_block_start", buildJsonObject { put("type", "content_block_start"); put("index", idx); put("content_block", start) })
                    initialContent(sb, idx, event.index, event.block)
                }
                is StreamEvent.BlockDelta -> {
                    val idx = indexOf[event.index] ?: return
                    if (event.delta is Delta.Signature) signed += event.index
                    val delta = when (val dl = event.delta) {
                        is Delta.Text -> buildJsonObject { put("type", "text_delta"); put("text", dl.text) }
                        is Delta.Reasoning -> buildJsonObject { put("type", "thinking_delta"); put("thinking", dl.text) }
                        is Delta.ReasoningSummary -> buildJsonObject { put("type", "thinking_delta"); put("thinking", dl.text) }
                        is Delta.Signature -> buildJsonObject { put("type", "signature_delta"); put("signature", dl.signature) }
                        is Delta.Arguments -> buildJsonObject { put("type", "input_json_delta"); put("partial_json", dl.json) }
                        is Delta.Citation -> buildJsonObject { put("type", "citations_delta"); put("citation", dl.citation) }
                    }
                    emit(sb, "content_block_delta", buildJsonObject { put("type", "content_block_delta"); put("index", idx); put("delta", delta) })
                }
                is StreamEvent.BlockStop -> if (openIr == event.index) closeOpen(sb)
                is StreamEvent.Finish -> finish = event
                is StreamEvent.Error -> {
                    ensureStarted(sb)
                    emit(sb, "error", encodeError(event.error))
                    done = true
                }
            }
        }

        private fun startBlock(b: Block): JsonObject? = when (b) {
            is TextBlock -> buildJsonObject { put("type", "text"); put("text", "") }
            is RefusalBlock -> {
                d.warn(DiagnosticCodes.REFUSAL_AS_TEXT, "stream", "refusal sent as text")
                buildJsonObject { put("type", "text"); put("text", "") }
            }
            is ReasoningBlock -> if (b.redactedData != null) buildJsonObject { put("type", "redacted_thinking"); put("data", b.redactedData) }
                else buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "") }
            is ToolCallBlock -> buildJsonObject { put("type", "tool_use"); put("id", b.id); put("name", b.name); put("input", buildJsonObject { }) }
            is HostedToolBlock -> if (b.sourceFormat == WireFormat.ANTHROPIC) b.raw else {
                d.warn(DiagnosticCodes.HOSTED_TOOL_OMITTED, "stream", "hosted tool activity '${b.type}' omitted"); null
            }
            is RawBlock -> if (b.sourceFormat == WireFormat.ANTHROPIC) b.raw else {
                d.warn(DiagnosticCodes.UNSUPPORTED_CONTENT, "stream", "'${b.type}' block omitted"); null
            }
            else -> null
        }

        private fun closeOpen(sb: StringBuilder) {
            val ir = openIr ?: return
            openIr = null
            val idx = indexOf[ir] ?: return
            // thinking 块收尾必须有 signature_delta（SDK 的累加器依赖它）；上游没给就补一个空的
            val b = blocks[ir]
            if (b is ReasoningBlock && b.redactedData == null && ir !in signed) {
                emitDelta(sb, idx, buildJsonObject { put("type", "signature_delta"); put("signature", "") })
            }
            emit(sb, "content_block_stop", buildJsonObject { put("type", "content_block_stop"); put("index", idx) })
        }

        /** 起始就带内容的块（如 Gemini 一次给出完整的 functionCall）紧跟一个 delta。 */
        private fun initialContent(sb: StringBuilder, idx: Int, ir: Int, b: Block) {
            when (b) {
                is TextBlock -> if (b.text.isNotEmpty()) emitTextDelta(sb, idx, b.text)
                is RefusalBlock -> if (b.text.isNotEmpty()) emitTextDelta(sb, idx, b.text)
                is ReasoningBlock -> if (b.redactedData == null) {
                    if (b.text.isNotEmpty()) emitDelta(sb, idx, buildJsonObject { put("type", "thinking_delta"); put("thinking", b.text) })
                    b.signature?.let { emitDelta(sb, idx, buildJsonObject { put("type", "signature_delta"); put("signature", it) }); signed += ir }
                }
                is ToolCallBlock -> if (b.arguments.isNotEmpty()) emitDelta(sb, idx, buildJsonObject { put("type", "input_json_delta"); put("partial_json", b.arguments) })
                else -> {}
            }
        }

        private fun emitDelta(sb: StringBuilder, idx: Int, delta: JsonObject) = emit(sb, "content_block_delta", buildJsonObject {
            put("type", "content_block_delta"); put("index", idx); put("delta", delta)
        })

        private fun emitTextDelta(sb: StringBuilder, idx: Int, text: String) = emit(sb, "content_block_delta", buildJsonObject {
            put("type", "content_block_delta"); put("index", idx)
            put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
        })

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            val u = usage
            emit(sb, "message_start", buildJsonObject {
                put("type", "message_start")
                put("message", buildJsonObject {
                    put("id", id); put("type", "message"); put("role", "assistant"); put("model", model)
                    put("content", buildJsonArray { })
                    put("stop_reason", JsonNull); put("stop_sequence", JsonNull)
                    put("usage", if (u != null) encodeUsage(u) else buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
                })
            })
        }

        private fun emit(sb: StringBuilder, name: String, data: JsonObject) { sb.append(SseWriter.event(name, data.toString())) }

        override fun finish(truncated: Boolean): String {
            if (done) return ""
            done = true
            if (truncated) return ""  // 不伪造 message_stop：客户端据此知道流被截断
            val sb = StringBuilder()
            done = false
            for (e in sequential.flush()) encodeOne(e, sb)
            done = true
            ensureStarted(sb)
            closeOpen(sb)
            val f = finish
            emit(sb, "message_delta", buildJsonObject {
                put("type", "message_delta")
                put("delta", buildJsonObject {
                    put("stop_reason", stopReasonString(f?.reason ?: FinishReason.Stop, f?.raw) ?: "end_turn")
                    put("stop_sequence", JsonNull)
                })
                put("usage", encodeUsage(usage ?: Usage.UNKNOWN))
            })
            emit(sb, "message_stop", buildJsonObject { put("type", "message_stop") })
            return sb.toString()
        }
    }
}
