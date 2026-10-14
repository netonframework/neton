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
 * Gemini 编码选项（spec §4.5）。
 *
 * @property signatureBypass 当前轮每一步缺少真实签名的第一个 functionCall 写入官方占位值（有损兼容）。
 *   **调用方必须已经通过 `isBypassVerified` 确认该接口、模型与渠道有验证记录**，编解码器不做这个判断。
 * @property requireSignatures 目标型号是否校验签名；null 时按 `request.model` 推断（gemini-3 及之后）。
 */
data class GeminiEncodeOptions(
    val signatureBypass: Boolean = false,
    val requireSignatures: Boolean? = null,
)

/**
 * Gemini generateContent。
 *
 * 模型名与是否流式在 URL 上，不在报文里：解码得到的 `model` 为 null、`stream` 为 false，由调用方补上。
 * 读取同时接受 camelCase 与 snake_case，写出用 camelCase。
 */
object GeminiCodec : ProtocolCodec {
    override val format = WireFormat.GEMINI
    private val F = WireFormat.GEMINI
    private val NS = ProviderMetadata.GEMINI
    private val SIG = ProviderMetadata.THOUGHT_SIGNATURE

    /** Google 官方占位值之一（spec §4.5.2，官方强烈不鼓励）。 */
    const val SIGNATURE_BYPASS_VALUE = "context_engineering_is_the_way_to_go"
    private const val HAS_ID = "hasId"
    /** 本编解码器按顺序生成的 ID（Gemini 没给），只在内部回配用，不写出。 */
    private const val SYNTHETIC_ID = "syntheticId"
    /** 标记推理块来自 Gemini 的 thought 部分（只有这种才写回 Gemini）。 */
    private const val THOUGHT = "thought"

    private val REQUEST_KEYS = setOf(
        "model", "contents", "systemInstruction", "system_instruction", "tools", "toolConfig", "tool_config",
        "generationConfig", "generation_config",
    )
    private val GEN_KEYS = setOf(
        "temperature", "topP", "top_p", "topK", "top_k", "maxOutputTokens", "max_output_tokens", "stopSequences", "stop_sequences",
        "seed", "candidateCount", "candidate_count", "presencePenalty", "presence_penalty", "frequencyPenalty", "frequency_penalty",
        "responseMimeType", "response_mime_type", "responseSchema", "response_schema", "responseJsonSchema", "response_json_schema",
        "thinkingConfig", "thinking_config",
    )

    private fun JsonObject.g(camel: String): JsonElement? = this[camel] ?: this[snake(camel)]
    private fun JsonObject.gObj(camel: String): JsonObject? = g(camel) as? JsonObject
    private fun JsonObject.gArr(camel: String): JsonArray? = g(camel) as? JsonArray
    private fun JsonObject.gStr(camel: String): String? = (g(camel) as? JsonPrimitive)?.contentOrNull
    private fun snake(camel: String): String = camel.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    // ───────────────────────── 请求 ─────────────────────────

    override fun decodeRequest(body: JsonObject, diagnostics: Diagnostics): ChatRequest {
        val sys = body.gObj("systemInstruction")
        val instructions = sys?.gArr("parts")?.objects()?.mapNotNull { p -> p.gStr("text")?.let { Instruction(it) } } ?: emptyList()
        val ids = CallIds()
        val messages = (body.arr("contents") ?: JsonArray(emptyList())).objects().mapIndexed { i, c ->
            val role = if (c.str("role") == "model") Role.Assistant else Role.User
            Message(role, (c.gArr("parts") ?: JsonArray(emptyList())).objects().mapIndexed { j, p -> decodePart(p, "contents[$i].parts[$j]", ids) },
                metadata = ProviderMetadata.EMPTY.withExtra(F, c, setOf("role", "parts")))
        }
        val gen = body.gObj("generationConfig") ?: JsonObject(emptyMap())
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, REQUEST_KEYS)
        JsonObject(gen.extraFields(GEN_KEYS)).takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "generationConfigExtra", it) }
        val thinking = gen.gObj("thinkingConfig")
        if (thinking?.gStr("thinkingLevel") != null) meta = meta.with(NS, "thinkingField", "level")
        val fcc = body.gObj("toolConfig")?.gObj("functionCallingConfig")
        val toolChoice = fcc?.let(::decodeToolChoice)
        if (body.g("toolConfig") != null && toolChoice == null) meta = meta.with(NS, "toolConfig", body.g("toolConfig"))
        return ChatRequest(
            // 官方报文里没有 model（在 URL 上）；网关等宿主会把它放进 body，读出来
            model = body.str("model"),
            instructions = instructions,
            messages = messages,
            tools = body.arr("tools")?.objects()?.flatMap(::decodeTools) ?: emptyList(),
            options = CallOptions(
                maxOutputTokens = (gen.g("maxOutputTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                temperature = (gen.g("temperature") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                topP = (gen.g("topP") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                topK = (gen.g("topK") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt(),
                stop = gen.gArr("stopSequences")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                seed = (gen.g("seed") as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                presencePenalty = (gen.g("presencePenalty") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                frequencyPenalty = (gen.g("frequencyPenalty") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                candidateCount = (gen.g("candidateCount") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                toolChoice = toolChoice,
                responseFormat = decodeResponseFormat(gen),
                reasoning = thinking?.let(::decodeThinking),
            ),
            metadata = meta,
        )
    }

    /** Gemini 的 functionCall 没有 ID 时按出现顺序生成，functionResponse 按名称回配最早未回配的调用（spec §4.4）。 */
    private class CallIds {
        private var counter = 0
        private val pending = LinkedHashMap<String, ArrayDeque<String>>()

        fun forCall(name: String, given: String?): String {
            val id = given ?: "call_${name}_${counter}"
            counter++
            pending.getOrPut(name) { ArrayDeque() }.addLast(id)
            return id
        }

        fun forResponse(name: String, given: String?): String {
            if (given != null) { pending[name]?.remove(given); return given }
            return pending[name]?.removeFirstOrNull() ?: "call_${name}_unmatched"
        }
    }

    private fun decodePart(p: JsonObject, path: String, ids: CallIds): Block {
        var meta = ProviderMetadata.EMPTY
        p.gStr("thoughtSignature")?.takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, SIG, it) }
        val known = setOf("text", "thought", "thoughtSignature", "thought_signature", "inlineData", "inline_data", "fileData", "file_data",
            "functionCall", "function_call", "functionResponse", "function_response")
        meta = meta.withExtra(F, p, known)
        p.gStr("text")?.let { text ->
            return if (p.bool("thought") == true) ReasoningBlock(text, metadata = meta.with(NS, THOUGHT, JsonPrimitive(true))) else TextBlock(text, metadata = meta)
        }
        p.gObj("inlineData")?.let { d ->
            val src = MediaSource.Base64(d.gStr("data") ?: "", d.gStr("mimeType"))
            return if (src.mediaType?.startsWith("image/") == true) ImageBlock(src, metadata = meta) else FileBlock(src, metadata = meta)
        }
        p.gObj("fileData")?.let { d ->
            val src = MediaSource.FileRef(d.gStr("fileUri") ?: "", d.gStr("mimeType"))
            return if (src.mediaType?.startsWith("image/") == true) ImageBlock(src, metadata = meta) else FileBlock(src, metadata = meta)
        }
        p.gObj("functionCall")?.let { fc ->
            val name = fc.str("name") ?: ""
            val given = fc.str("id")
            meta = meta.with(NS, if (given != null) HAS_ID else SYNTHETIC_ID, JsonPrimitive(true))
            return ToolCallBlock(ids.forCall(name, given), name, (fc["args"] ?: JsonObject(emptyMap())).toString(), ToolKind.Function, meta)
        }
        p.gObj("functionResponse")?.let { fr ->
            val name = fr.str("name") ?: ""
            val given = fr.str("id")
            if (given != null) meta = meta.with(NS, HAS_ID, JsonPrimitive(true))
            val response = fr["response"]
            // 网关与主流转换都把非对象结果包成 {"content": ...}；这里拆回来，跨协议时文本不带包装
            val text = (response as? JsonObject)?.takeIf { it.keys == setOf("content") }?.get("content")
                ?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                ?: response?.toString() ?: ""
            if ((response as? JsonObject)?.keys == setOf("content")) meta = meta.with(NS, "wrappedResponse", JsonPrimitive(true))
            return ToolResultBlock(ids.forResponse(name, given), listOf(TextBlock(text)), toolName = name, metadata = meta)
        }
        val type = p.keys.firstOrNull { it != "thoughtSignature" && it != "thought_signature" } ?: ""
        return if (type == "executableCode" || type == "codeExecutionResult" || type == "executable_code" || type == "code_execution_result") {
            HostedToolBlock(type, p, F)
        } else RawBlock(type, p, F)
    }

    private fun decodeTools(t: JsonObject): List<ToolSpec> {
        val fns = t.gArr("functionDeclarations")
        if (fns != null) return fns.objects().map { fn ->
            FunctionTool(fn.str("name") ?: "", fn.str("description"), fn.g("parametersJsonSchema") ?: fn["parameters"],
                metadata = ProviderMetadata.EMPTY.withExtra(F, fn, setOf("name", "description", "parameters", "parametersJsonSchema", "parameters_json_schema"))
                    .let { if (fn.g("parametersJsonSchema") != null) it.with(NS, "jsonSchema", JsonPrimitive(true)) else it })
        }
        return listOf(HostedTool(t.keys.firstOrNull() ?: "", t, F))
    }

    private fun decodeToolChoice(fcc: JsonObject): ToolChoice? {
        val allowed = fcc.gArr("allowedFunctionNames")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return when (fcc.str("mode")) {
            "AUTO" -> if (allowed.isEmpty()) ToolChoice.Auto else null
            "NONE" -> ToolChoice.None
            "ANY" -> when (allowed.size) {
                0 -> ToolChoice.Required
                1 -> ToolChoice.Named(allowed[0])
                else -> null
            }
            else -> null
        }
    }

    private fun decodeResponseFormat(gen: JsonObject): ResponseFormat? {
        val mime = gen.gStr("responseMimeType") ?: return null
        if (mime != "application/json") return if (mime == "text/plain") ResponseFormat.Text else null
        val schema = gen.g("responseJsonSchema") ?: gen.g("responseSchema")
        return if (schema != null) ResponseFormat.JsonSchema("response", schema) else ResponseFormat.JsonObject
    }

    private fun decodeThinking(t: JsonObject): ReasoningOptions {
        val budget = (t.g("thinkingBudget") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return ReasoningOptions(
            mode = when (budget) {
                0 -> ReasoningMode.Disabled
                -1 -> ReasoningMode.Adaptive
                null -> null
                else -> ReasoningMode.Enabled
            },
            effort = t.gStr("thinkingLevel")?.lowercase(),
            budgetTokens = budget?.takeIf { it > 0 },
            includeThoughts = (t.g("includeThoughts") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
        )
    }

    override fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics): JsonObject =
        encodeRequest(request, diagnostics, GeminiEncodeOptions())

    fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics, options: GeminiEncodeOptions): JsonObject = tracking {
        val d = diagnostics
        val o = request.options
        val systemTexts = request.instructions.map { it.text }.toMutableList()
        put("contents", JsonArray(encodeContents(request, d, options, systemTexts)))
        if (systemTexts.isNotEmpty()) put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { systemTexts.forEach { t -> add(buildJsonObject { put("text", t) }) } })
        })
        val tools = encodeTools(request.tools, d)
        if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        val keptToolConfig = request.metadata[NS, "toolConfig"]
        when {
            keptToolConfig != null -> put("toolConfig", keptToolConfig)
            o.toolChoice != null && (tools.isNotEmpty() || o.toolChoice == ToolChoice.None) -> put("toolConfig", buildJsonObject {
                put("functionCallingConfig", buildJsonObject {
                    when (val c = o.toolChoice) {
                        ToolChoice.Auto -> put("mode", "AUTO")
                        ToolChoice.None -> put("mode", "NONE")
                        ToolChoice.Required -> put("mode", "ANY")
                        is ToolChoice.Named -> { put("mode", "ANY"); put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(c.name)) }) }
                    }
                })
            })
        }
        if (o.parallelToolCalls == false) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "parallel_tool_calls", "Gemini has no switch for parallel function calls")
        val gen = tracking {
            put("temperature", o.temperature)
            put("topP", o.topP)
            put("topK", o.topK)
            put("maxOutputTokens", o.maxOutputTokens)
            if (o.stop.isNotEmpty()) put("stopSequences", JsonArray(o.stop.map(::JsonPrimitive)))
            put("seed", o.seed)
            put("candidateCount", o.candidateCount)
            put("presencePenalty", o.presencePenalty)
            put("frequencyPenalty", o.frequencyPenalty)
            when (val rf = o.responseFormat) {
                ResponseFormat.JsonObject -> put("responseMimeType", "application/json")
                is ResponseFormat.JsonSchema -> {
                    put("responseMimeType", "application/json")
                    put("responseJsonSchema", rf.schema)
                }
                ResponseFormat.Text -> put("responseMimeType", "text/plain")
                null -> {}
            }
            encodeThinking(o.reasoning, request.metadata)?.let { put("thinkingConfig", it) }
            (request.metadata[NS, "generationConfigExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
        }
        if (gen.isNotEmpty()) put("generationConfig", gen)
        if (o.user != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "user", "Gemini has no end-user field")
        putExtras(request.metadata, F)
    }

    private fun encodeThinking(r: ReasoningOptions?, meta: ProviderMetadata): JsonObject? {
        if (r == null) return null
        return tracking {
            when {
                r.mode == ReasoningMode.Disabled -> put("thinkingBudget", 0)
                meta.string(NS, "thinkingField") == "level" && r.effort != null -> put("thinkingLevel", r.effort)
                r.budgetTokens != null -> put("thinkingBudget", r.budgetTokens)
                r.mode == ReasoningMode.Adaptive -> put("thinkingBudget", -1)
                r.effort != null -> Reasoning.budgetForEffort(r.effort)?.let { put("thinkingBudget", it) }
            }
            val include = r.includeThoughts ?: if (r.mode != ReasoningMode.Disabled && keys.isNotEmpty()) true else null
            put("includeThoughts", include)
        }.takeIf { it.isNotEmpty() }
    }

    private fun encodeContents(request: ChatRequest, d: Diagnostics, options: GeminiEncodeOptions, systemTexts: MutableList<String>): List<JsonObject> {
        val callNames = HashMap<String, String>()
        request.messages.forEach { m -> m.content.filterIsInstance<ToolCallBlock>().forEach { callNames[it.id] = it.name } }
        // 当前轮 = 最后一条含真实用户输入（非工具结果）的消息之后；Google 只校验当前轮
        val currentTurnStart = request.messages.indexOfLast { m ->
            (m.role == Role.User) && m.content.any { it !is ToolResultBlock }
        }
        val requireSignatures = options.requireSignatures ?: isSignatureValidatingModel(request.model)
        val list = mutableListOf<JsonObject>()
        request.messages.forEachIndexed { i, m ->
            val path = "messages[$i]"
            when (m.role) {
                Role.System, Role.Developer -> {
                    d.warn(DiagnosticCodes.INSTRUCTION_MOVED, path, "mid-conversation system message moved into systemInstruction")
                    systemTexts += m.content.plainText("\n")
                }
                else -> {
                    val role = if (m.role == Role.Assistant) "model" else "user"
                    val parts = mutableListOf<JsonObject>()
                    m.content.forEachIndexed { j, b -> encodePart(b, "$path.content[$j]", d, callNames, m.role)?.let { parts += it } }
                    if (m.role == Role.Assistant) applySignatureRules(parts, i > currentTurnStart, requireSignatures, options, d, path)
                    if (parts.isNotEmpty()) list += tracking { put("role", role); put("parts", JsonArray(parts)); putExtras(m.metadata, F) }
                }
            }
        }
        // 相邻同角色合并：连续的 functionResponse 必须在同一个 user content 里
        val merged = mutableListOf<JsonObject>()
        for (c in list) {
            val last = merged.lastOrNull()
            if (last != null && last["role"] == c["role"]) {
                merged[merged.size - 1] = JsonObject(last + ("parts" to JsonArray(last.arr("parts")!! + c.arr("parts")!!)))
            } else merged += c
        }
        return merged
    }

    /** 每一步（一条 model 消息）的第一个 functionCall：有真实签名原样保留；缺失时按选项补占位值或记诊断。 */
    private fun applySignatureRules(parts: MutableList<JsonObject>, inCurrentTurn: Boolean, require: Boolean, options: GeminiEncodeOptions, d: Diagnostics, path: String) {
        val first = parts.indexOfFirst { it.containsKey("functionCall") }
        if (first < 0 || parts[first].containsKey("thoughtSignature") || !inCurrentTurn) return
        when {
            options.signatureBypass -> {
                parts[first] = JsonObject(parts[first] + ("thoughtSignature" to JsonPrimitive(SIGNATURE_BYPASS_VALUE)))
                d.error(DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_BYPASSED, "$path.functionCall", "official placeholder signature written; reasoning state is not restored")
            }
            require -> d.error(DiagnosticCodes.GEMINI_THOUGHT_SIGNATURE_MISSING, "$path.functionCall", "first functionCall of the step has no thought signature")
        }
    }

    /** Gemini 3 及之后的型号校验签名（spec §4.5）。模型未知时按校验处理，宁可多报诊断。 */
    fun isSignatureValidatingModel(model: String?): Boolean {
        if (model == null) return true
        val m = Regex("gemini-(\\d+)").find(model.substringAfterLast('/')) ?: return true
        return (m.groupValues[1].toIntOrNull() ?: 3) >= 3
    }

    private fun encodePart(b: Block, path: String, d: Diagnostics, callNames: Map<String, String>, role: Role, forResponse: Boolean = false): JsonObject? {
        val sig = b.metadata.string(NS, SIG)
        if (b.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
            d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, path, "cache_control has no Gemini equivalent (use cachedContent)")
        }
        val part = when (b) {
            is TextBlock -> tracking { put("text", b.text); put("thoughtSignature", sig); putExtras(b.metadata, F) }
            is ReasoningBlock -> {
                if (b.metadata[NS, THOUGHT] != null || (forResponse && !b.redacted)) {
                    tracking { put("text", b.text.ifEmpty { b.summary.joinToString("\n") }); put("thought", true); put("thoughtSignature", sig); putExtras(b.metadata, F) }
                } else {
                    d.warn(DiagnosticCodes.REASONING_DROPPED, path, "reasoning from another protocol is not sent to Gemini")
                    null
                }
            }
            is ImageBlock -> mediaPart(b.source, b.metadata, path, d)
            is FileBlock -> mediaPart(b.source, b.metadata, path, d)
            is ToolCallBlock -> tracking {
                put("functionCall", tracking {
                    // 发给客户端时带上调用 ID，客户端可在 functionResponse.id 回传，同名并行调用也能对应；
                    // 发给上游时只写来自 Gemini 的 ID，不把其他协议的 ID 塞给它
                    if (b.metadata[NS, HAS_ID] != null || (forResponse && b.id.isNotEmpty() && b.metadata[NS, SYNTHETIC_ID] == null)) put("id", b.id)
                    put("name", b.name)
                    put("args", J.parseOrString(b.arguments.ifBlank { "{}" }).let { it as? JsonObject ?: buildJsonObject { put("input", b.arguments) } })
                })
                put("thoughtSignature", sig)
                putExtras(b.metadata, F)
            }
            is ToolResultBlock -> {
                val name = b.toolName ?: callNames[b.callId] ?: run {
                    d.error(DiagnosticCodes.MALFORMED_INPUT, path, "tool result ${b.callId} has no matching call; name unknown")
                    b.callId
                }
                val nonText = b.content.filter { it !is TextBlock }
                if (nonText.isNotEmpty()) d.warn(DiagnosticCodes.TOOL_RESULT_CONTENT_FLATTENED, path, "non-text tool result content dropped")
                if (b.isError) d.warn("tool_error_flag_dropped", path, "Gemini functionResponse has no error flag")
                val text = b.content.plainText("\n")
                val parsed = runCatching { J.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val wrapped = b.metadata[NS, "wrappedResponse"] != null
                tracking {
                    put("functionResponse", tracking {
                        if (b.metadata[NS, HAS_ID] != null) put("id", b.callId)
                        put("name", name)
                        put("response", if (parsed != null && !wrapped) parsed else buildJsonObject { put("content", text) })
                    })
                    putExtras(b.metadata, F)
                }
            }
            is RefusalBlock -> {
                d.warn(DiagnosticCodes.REFUSAL_AS_TEXT, path, "refusal sent as text"); buildJsonObject { put("text", b.text) }
            }
            is HostedToolBlock -> if (b.sourceFormat == F) b.raw else {
                d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool activity '${b.type}' from ${b.sourceFormat.id} dropped"); null
            }
            is RawBlock -> if (b.sourceFormat == F) b.raw else {
                d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, path, "'${b.type}' block from ${b.sourceFormat.id} dropped"); null
            }
        }
        if (role != Role.Assistant && b is ToolCallBlock) d.warn(DiagnosticCodes.MALFORMED_INPUT, path, "tool call outside a model turn")
        return part
    }

    private fun mediaPart(s: MediaSource, meta: ProviderMetadata, path: String, d: Diagnostics): JsonObject = tracking {
        when (s) {
            is MediaSource.Base64 -> put("inlineData", buildJsonObject { put("mimeType", s.mediaType ?: "application/octet-stream"); put("data", s.data) })
            is MediaSource.Url -> put("fileData", buildJsonObject { s.mediaType?.let { put("mimeType", it) }; put("fileUri", s.url) })
            is MediaSource.FileRef -> put("fileData", buildJsonObject { s.mediaType?.let { put("mimeType", it) }; put("fileUri", s.fileId) })
        }
        put("thoughtSignature", meta.string(NS, SIG))
        putExtras(meta, F)
    }

    private fun encodeTools(tools: List<ToolSpec>, d: Diagnostics): List<JsonObject> {
        val decls = mutableListOf<JsonObject>()
        val others = mutableListOf<JsonObject>()
        tools.forEachIndexed { i, t ->
            when (t) {
                is FunctionTool -> decls += tracking {
                    put("name", t.name)
                    put("description", t.description)
                    t.parameters?.let { put(if (t.metadata[NS, "jsonSchema"] != null) "parametersJsonSchema" else "parameters", it) }
                    if (t.strict == true) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "tools[$i].strict", "Gemini has no strict function schema flag")
                    putExtras(t.metadata, F)
                }
                is CustomTool -> {
                    d.warn(DiagnosticCodes.CUSTOM_TOOL_AS_FUNCTION, "tools[$i]", "custom tool sent as a function with a single string 'input'")
                    decls += buildJsonObject {
                        put("name", t.name)
                        t.description?.let { put("description", it) }
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject { put("input", buildJsonObject { put("type", "string") }) })
                            put("required", buildJsonArray { add(JsonPrimitive("input")) })
                        })
                    }
                }
                is HostedTool -> if (t.sourceFormat == F) others += t.raw else {
                    d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, "tools[$i]", "hosted tool '${t.type}' from ${t.sourceFormat.id} has no Gemini equivalent")
                }
            }
        }
        return (if (decls.isEmpty()) emptyList() else listOf(buildJsonObject { put("functionDeclarations", JsonArray(decls)) })) + others
    }

    // ───────────────────────── 响应 ─────────────────────────

    override fun decodeResponse(body: JsonObject, diagnostics: Diagnostics): ChatResponse {
        val candidates = body.arr("candidates")?.objects() ?: emptyList()
        if (candidates.size > 1) diagnostics.warn(DiagnosticCodes.N_GT_1_UNSUPPORTED, "candidates", "only the first candidate is kept")
        val c = candidates.firstOrNull()
        val ids = CallIds()
        val content = c?.obj("content")?.gArr("parts")?.objects()?.mapIndexed { j, p -> decodePart(p, "candidates[0].content.parts[$j]", ids) } ?: emptyList()
        val raw = c?.gStr("finishReason")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, setOf("candidates", "usageMetadata", "usage_metadata", "modelVersion", "model_version", "responseId", "response_id"))
        c?.let { JsonObject(it.extraFields(setOf("content", "finishReason", "finish_reason", "index"))).takeIf { e -> e.isNotEmpty() }?.let { e -> meta = meta.with(NS, "candidateExtra", e) } }
        return ChatResponse(
            id = body.gStr("responseId"),
            model = body.gStr("modelVersion"),
            content = content,
            finishReason = raw?.let { finishReasonOf(it, content.any { b -> b is ToolCallBlock }) },
            rawFinishReason = raw,
            usage = body.gObj("usageMetadata")?.let { decodeUsage(it, UsageCompleteness.COMPLETE) },
            metadata = meta,
        )
    }

    override fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics): JsonObject = tracking {
        val callNames = response.content.filterIsInstance<ToolCallBlock>().associate { it.id to it.name }
        val parts = response.content.mapIndexedNotNull { j, b -> encodePart(b, "content[$j]", diagnostics, callNames, Role.Assistant, forResponse = true) }
        put("candidates", buildJsonArray {
            add(tracking {
                put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
                put("finishReason", finishReasonString(response.finishReason, response.rawFinishReason) ?: "STOP")
                put("index", 0)
                (response.metadata[NS, "candidateExtra"] as? JsonObject)?.forEach { (k, v) -> if (k !in keys) put(k, v) }
            })
        })
        response.usage?.let { put("usageMetadata", encodeUsage(it)) }
        put("modelVersion", response.model)
        put("responseId", response.id)
        putExtras(response.metadata, F)
    }

    internal fun finishReasonOf(raw: String, hasToolCalls: Boolean): FinishReason = when (raw) {
        "STOP" -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Stop
        "MAX_TOKENS" -> FinishReason.Length
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT" -> FinishReason.ContentFilter
        "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS" -> FinishReason.Error
        else -> FinishReason.Other
    }

    private val FINISH_REASONS = setOf("STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
        "IMAGE_SAFETY", "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "OTHER", "LANGUAGE")

    internal fun finishReasonString(reason: FinishReason?, raw: String?): String? {
        if (raw != null && raw in FINISH_REASONS) return raw
        return when (reason) {
            null -> null
            FinishReason.Stop, FinishReason.ToolCalls, FinishReason.PauseTurn -> "STOP"
            FinishReason.Length -> "MAX_TOKENS"
            FinishReason.ContentFilter, FinishReason.Refusal -> "SAFETY"
            FinishReason.Error -> "MALFORMED_FUNCTION_CALL"
            FinishReason.Other -> "OTHER"
        }
    }

    /** Gemini 的 candidatesTokenCount 不含思考：换算到 IR 口径时把 thoughtsTokenCount 加进 output。 */
    internal fun decodeUsage(u: JsonObject, completeness: UsageCompleteness): Usage {
        fun n(k: String) = (u.g(k) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val prompt = n("promptTokenCount")
        val toolPrompt = n("toolUsePromptTokenCount")
        val candidates = n("candidatesTokenCount")
        val thoughts = n("thoughtsTokenCount")
        return Usage(
            inputTokens = prompt?.let { it + (toolPrompt ?: 0) },
            outputTokens = if (candidates == null && thoughts == null) null else (candidates ?: 0) + (thoughts ?: 0),
            cacheReadTokens = n("cachedContentTokenCount"),
            reasoningTokens = thoughts,
            completeness = completeness,
            raw = RawUsage(F, u),
        )
    }

    internal fun encodeUsage(u: Usage): JsonObject {
        if (u.raw?.format == F) return u.raw.json
        return tracking {
            put("promptTokenCount", u.inputTokens)
            put("candidatesTokenCount", u.outputTokens?.let { it - (u.reasoningTokens ?: 0) })
            put("thoughtsTokenCount", u.reasoningTokens)
            put("cachedContentTokenCount", u.cacheReadTokens)
            if (u.inputTokens != null && u.outputTokens != null) put("totalTokenCount", u.inputTokens + u.outputTokens)
        }
    }

    // ───────────────────────── 错误 ─────────────────────────

    override fun encodeError(error: ProtocolError): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("code", error.status ?: ProtocolError.statusFor(error.kind))
            put("message", error.message)
            put("status", when (error.kind) {
                ErrorKind.InvalidRequest, ErrorKind.ContextLengthExceeded, ErrorKind.ContentFilter -> "INVALID_ARGUMENT"
                ErrorKind.Unauthorized -> "UNAUTHENTICATED"
                ErrorKind.Forbidden -> "PERMISSION_DENIED"
                ErrorKind.NotFound -> "NOT_FOUND"
                ErrorKind.RateLimited -> "RESOURCE_EXHAUSTED"
                ErrorKind.Overloaded -> "UNAVAILABLE"
                ErrorKind.Timeout -> "DEADLINE_EXCEEDED"
                else -> "INTERNAL"
            })
        })
    }

    override fun decodeError(status: Int, body: String): ProtocolError {
        val parsed = J.parseObject(body) ?: (runCatching { J.json.parseToJsonElement(body) as? JsonArray }.getOrNull()?.firstOrNull() as? JsonObject)
        val err = parsed?.obj("error")
        val st = err?.str("status")
        val message = err?.str("message") ?: "upstream error (HTTP $status)"
        val kind = when (st) {
            "INVALID_ARGUMENT", "FAILED_PRECONDITION" -> if (message.contains("token", true) && message.contains("exceed", true)) ErrorKind.ContextLengthExceeded else ErrorKind.InvalidRequest
            "UNAUTHENTICATED" -> ErrorKind.Unauthorized
            "PERMISSION_DENIED" -> ErrorKind.Forbidden
            "NOT_FOUND" -> ErrorKind.NotFound
            "RESOURCE_EXHAUSTED" -> ErrorKind.RateLimited
            "UNAVAILABLE" -> ErrorKind.Overloaded
            "DEADLINE_EXCEEDED" -> ErrorKind.Timeout
            "INTERNAL" -> ErrorKind.ServerError
            else -> ProtocolError.kindForStatus(if (status != 0) status else err?.int("code") ?: 0)
        }
        return ProtocolError(kind, message, status, st, body, F)
    }

    // ───────────────────────── 流式 ─────────────────────────

    override fun streamDecoder(diagnostics: Diagnostics): StreamDecoder = GeminiStreamDecoder(diagnostics)
    override fun streamEncoder(diagnostics: Diagnostics): StreamEncoder = GeminiStreamEncoder(diagnostics)

    private class GeminiStreamDecoder(private val d: Diagnostics) : StreamDecoder {
        private val seq = BlockSequencer()
        private val ids = CallIds()
        private var started = false
        private var toolCount = 0
        private var hasToolCalls = false
        private var pendingSignature: String? = null
        override var completed = false
            private set

        override fun decode(event: SseEvent): List<StreamEvent> {
            val data = event.data.trim()
            if (data.isEmpty()) return emptyList()
            val obj = J.parseObject(data) ?: return malformed(seq, d, WireFormat.GEMINI).also { completed = true }
            val out = mutableListOf<StreamEvent>()
            obj.obj("error")?.let {
                closeOpen(out)
                out += StreamEvent.Error(decodeError(0, obj.toString()).copy(status = null))
                completed = true
                return out
            }
            if (!started) {
                started = true
                out += StreamEvent.MessageStart(obj.gStr("responseId"), obj.gStr("modelVersion"))
            }
            val c = obj.arr("candidates")?.objects()?.firstOrNull()
            c?.obj("content")?.gArr("parts")?.objects()?.forEach { p -> decodeStreamPart(p, out) }
            val finish = c?.gStr("finishReason")
            obj.gObj("usageMetadata")?.let {
                out += StreamEvent.UsageUpdate(decodeUsage(it, if (finish != null) UsageCompleteness.COMPLETE else UsageCompleteness.PARTIAL))
            }
            if (finish != null) {
                closeOpen(out)
                out += StreamEvent.Finish(finishReasonOf(finish, hasToolCalls), finish)
                completed = true
            }
            return out
        }

        private fun decodeStreamPart(p: JsonObject, out: MutableList<StreamEvent>) {
            val sig = p.gStr("thoughtSignature")?.takeIf { it.isNotEmpty() }
            val fc = p.gObj("functionCall")
            if (fc != null) {
                closeOpen(out)
                hasToolCalls = true
                val name = fc.str("name") ?: ""
                var meta = ProviderMetadata.EMPTY
                sig?.let { meta = meta.with(NS, SIG, it) }
                meta = meta.with(NS, if (fc.str("id") != null) HAS_ID else SYNTHETIC_ID, JsonPrimitive(true))
                val block = ToolCallBlock(ids.forCall(name, fc.str("id")), name, (fc["args"] ?: JsonObject(emptyMap())).toString(), ToolKind.Function, meta)
                val key = "tool:${toolCount++}"
                seq.ensure(key, out) { block }
                seq.close(out, meta)
                return
            }
            val text = p.gStr("text")
            if (text != null) {
                val thought = p.bool("thought") == true
                val key = if (thought) "reasoning" else "text"
                if (text.isNotEmpty()) {
                    // 签名属于它所在的块：换块之前把上一块的签名交出去
                    if (!seq.isOpen(key)) closeOpen(out)
                    seq.delta(key, if (thought) Delta.Reasoning(text) else Delta.Text(text), out) { if (thought) ReasoningBlock() else TextBlock("") }
                }
                if (sig != null) {
                    if (!seq.isOpen && text.isEmpty()) {
                        // 只带签名的空文本部分（常见于流末尾）：挂到一个空文本块上，保证不丢
                        seq.ensure(key, out) { TextBlock("") }
                    }
                    pendingSignature = sig
                }
                return
            }
            closeOpen(out)
            val other = decodePart(p, "stream", ids)
            seq.ensure("other:${toolCount++}", out) { other }
            seq.close(out)
        }

        private fun closeOpen(out: MutableList<StreamEvent>) {
            val sig = pendingSignature
            pendingSignature = null
            seq.close(out, sig?.let { ProviderMetadata.EMPTY.with(NS, SIG, it) } ?: ProviderMetadata.EMPTY)
        }

        override fun finish(): List<StreamEvent> {
            val out = mutableListOf<StreamEvent>()
            closeOpen(out)
            return out
        }
    }

    private class GeminiStreamEncoder(private val d: Diagnostics) : StreamEncoder {
        private var id: String? = null
        private var model: String? = null
        private var done = false
        private val blocks = HashMap<Int, Block>()
        private val args = HashMap<Int, StringBuilder>()
        private var usage: Usage? = null
        private var finish: StreamEvent.Finish? = null
        private var signatureWarned = false

        override fun encode(event: StreamEvent): String {
            if (done) return ""
            val sb = StringBuilder()
            when (event) {
                is StreamEvent.MessageStart -> { id = event.id; model = event.model }
                is StreamEvent.BlockStart -> {
                    blocks[event.index] = event.block
                    when (val b = event.block) {
                        is ToolCallBlock -> args[event.index] = StringBuilder(b.arguments)
                        is TextBlock -> if (b.text.isNotEmpty()) part(sb, buildJsonObject { put("text", b.text) })
                        is ReasoningBlock -> if (b.text.isNotEmpty()) part(sb, buildJsonObject { put("text", b.text); put("thought", true) })
                        is HostedToolBlock -> if (b.sourceFormat == F) part(sb, b.raw) else d.warn(DiagnosticCodes.HOSTED_TOOL_OMITTED, "stream", "hosted tool activity '${b.type}' omitted")
                        is RawBlock -> if (b.sourceFormat == F) part(sb, b.raw)
                        else -> {}
                    }
                }
                is StreamEvent.BlockDelta -> when (val dl = event.delta) {
                    is Delta.Text -> part(sb, buildJsonObject { put("text", dl.text) })
                    is Delta.Reasoning -> part(sb, buildJsonObject { put("text", dl.text); put("thought", true) })
                    is Delta.ReasoningSummary -> part(sb, buildJsonObject { put("text", dl.text); put("thought", true) })
                    is Delta.Arguments -> args[event.index]?.append(dl.json)
                    is Delta.Signature -> if (!signatureWarned) {
                        signatureWarned = true
                        d.warn(DiagnosticCodes.SIGNATURE_DROPPED, "stream", "Anthropic thinking signatures cannot be sent to Gemini clients")
                    }
                    is Delta.Citation -> d.warn(DiagnosticCodes.CITATIONS_DROPPED, "stream", "streamed citations omitted")
                }
                is StreamEvent.BlockStop -> {
                    val b = blocks[event.index]
                    val sig = event.metadata.string(NS, SIG) ?: b?.metadata?.string(NS, SIG)
                    when (b) {
                        // functionCall 在 Gemini 里一次给出完整参数：收齐后整体输出
                        is ToolCallBlock -> part(sb, buildJsonObject {
                            put("functionCall", buildJsonObject {
                                if (b.id.isNotEmpty() && b.metadata[NS, SYNTHETIC_ID] == null) put("id", b.id)
                                put("name", b.name)
                                val a = args[event.index]?.toString().orEmpty().ifBlank { "{}" }
                                put("args", J.parseOrString(a) as? JsonObject ?: buildJsonObject { put("input", a) })
                            })
                            sig?.let { put("thoughtSignature", it) }
                        })
                        is TextBlock, is ReasoningBlock -> if (sig != null) part(sb, buildJsonObject {
                            put("text", "")
                            if (b is ReasoningBlock) put("thought", true)
                            put("thoughtSignature", sig)
                        })
                        else -> {}
                    }
                }
                is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(event.usage) ?: event.usage
                is StreamEvent.Finish -> finish = event
                is StreamEvent.Error -> {
                    sb.append(SseWriter.data(encodeError(event.error).toString()))
                    done = true
                }
            }
            return sb.toString()
        }

        private fun part(sb: StringBuilder, part: JsonObject) {
            sb.append(SseWriter.data(chunk(listOf(part), null, null).toString()))
        }

        private fun chunk(parts: List<JsonObject>, finishReason: String?, usage: Usage?): JsonObject = tracking {
            put("candidates", buildJsonArray {
                add(tracking {
                    put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
                    put("finishReason", finishReason)
                    put("index", 0)
                })
            })
            usage?.let { put("usageMetadata", encodeUsage(it)) }
            put("modelVersion", model)
            put("responseId", id)
        }

        override fun finish(truncated: Boolean): String {
            if (done) return ""
            done = true
            if (truncated) return ""
            val f = finish
            return SseWriter.data(chunk(emptyList(), finishReasonString(f?.reason ?: FinishReason.Stop, f?.raw) ?: "STOP", usage).toString())
        }
    }
}
