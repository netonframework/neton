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
 * OpenAI Responses。
 *
 * 输入与输出都是 item 列表：连续的 assistant 类 item（message、reasoning、function_call、托管工具调用）
 * 合成一条 IR assistant 消息，item ID 存在 `openai.itemId`，加密推理存在 [ReasoningBlock.encryptedContent]，
 * 编回本协议时原样写回（spec §4.4）。
 */
object OpenAiResponsesCodec : ProtocolCodec {
    override val format = WireFormat.OPENAI_RESPONSES
    private val F = WireFormat.OPENAI_RESPONSES
    private val NS = ProviderMetadata.OPENAI
    private val ITEM_ID = ProviderMetadata.ITEM_ID
    private const val INSTRUCTIONS_FIELD = "instructionsField"
    private const val ENCRYPTED = "encryptedContent"
    private const val FINAL_ITEM = "item"

    private val REQUEST_KEYS = setOf(
        "model", "input", "instructions", "tools", "tool_choice", "parallel_tool_calls", "temperature", "top_p",
        "max_output_tokens", "reasoning", "text", "stream", "user",
    )
    private val ASSISTANT_ITEM_TYPES = setOf("reasoning", "function_call", "custom_tool_call")

    // ───────────────────────── 请求 ─────────────────────────

    override fun decodeRequest(body: JsonObject, diagnostics: Diagnostics): ChatRequest {
        val instructions = mutableListOf<Instruction>()
        body.str("instructions")?.let {
            instructions += Instruction(it, Role.Developer, ProviderMetadata.EMPTY.with(NS, INSTRUCTIONS_FIELD, JsonPrimitive(true)))
        }
        val messages = mutableListOf<Message>()
        when (val input = body["input"]) {
            is JsonPrimitive -> input.contentOrNull?.let { messages += Message(Role.User, listOf(TextBlock(it))) }
            is JsonArray -> decodeItems(input.objects(), "input", diagnostics, messages, instructions)
            else -> {}
        }
        val text = body.obj("text")
        val reasoning = body.obj("reasoning")
        var meta = ProviderMetadata.EMPTY.withExtra(F, body, REQUEST_KEYS)
        text?.let { t -> JsonObject(t - "format").takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "textExtra", it) } }
        reasoning?.let { r -> JsonObject(r - "effort").takeIf { it.isNotEmpty() }?.let { meta = meta.with(NS, "reasoningExtra", it) } }
        val toolChoice = body["tool_choice"]?.let(::decodeToolChoice)
        if (body["tool_choice"] != null && toolChoice == null) meta = meta.with(NS, "toolChoice", body["tool_choice"])
        return ChatRequest(
            model = body.str("model"),
            instructions = instructions,
            messages = messages,
            tools = body.arr("tools")?.objects()?.map(::decodeTool) ?: emptyList(),
            options = CallOptions(
                maxOutputTokens = body.int("max_output_tokens"),
                temperature = body.double("temperature"),
                topP = body.double("top_p"),
                toolChoice = toolChoice,
                parallelToolCalls = body.bool("parallel_tool_calls"),
                responseFormat = text?.obj("format")?.let(::decodeFormat),
                reasoning = reasoning?.let {
                    ReasoningOptions(effort = it.str("effort"), includeThoughts = it["summary"]?.let { s -> s !is JsonNull })
                },
                user = body.str("user"),
            ),
            stream = body.bool("stream") ?: false,
            metadata = meta,
        )
    }

    /** item 列表 → IR 消息。连续的 assistant 类 item 合并，连续的工具输出合并。 */
    private fun decodeItems(items: List<JsonObject>, path: String, d: Diagnostics, out: MutableList<Message>, instructions: MutableList<Instruction>?) {
        var role: Role? = null
        val blocks = mutableListOf<Block>()
        var msgMeta = ProviderMetadata.EMPTY
        fun flush() {
            val r = role ?: return
            if (blocks.isNotEmpty()) out += Message(r, blocks.toList(), metadata = msgMeta)
            blocks.clear(); role = null; msgMeta = ProviderMetadata.EMPTY
        }
        fun into(r: Role, newBlocks: List<Block>, meta: ProviderMetadata = ProviderMetadata.EMPTY) {
            if (role != r || r == Role.User || r == Role.System || r == Role.Developer) { flush(); role = r; msgMeta = meta }
            blocks += newBlocks
        }
        items.forEachIndexed { i, item ->
            val p = "$path[$i]"
            val type = item.str("type") ?: if (item.containsKey("role")) "message" else null
            when (type) {
                "message" -> {
                    val r = when (item.str("role")) {
                        "assistant" -> Role.Assistant
                        "system" -> Role.System
                        "developer" -> Role.Developer
                        else -> Role.User
                    }
                    val itemMeta = ProviderMetadata.EMPTY.withExtra(F, item, setOf("type", "role", "content", "id"))
                    if ((r == Role.System || r == Role.Developer) && out.isEmpty() && role == null && instructions != null) {
                        instructions += Instruction(textOf(item["content"]), r, itemMeta)
                        return@forEachIndexed
                    }
                    val parts = decodeContent(item["content"], "$p.content", d).map { b ->
                        item.str("id")?.let { id -> b.withMeta(b.metadata.with(NS, ITEM_ID, id)) } ?: b
                    }
                    into(r, parts, if (r == Role.Assistant) ProviderMetadata.EMPTY else itemMeta)
                }
                "function_call", "custom_tool_call" -> into(Role.Assistant, listOf(decodeCallItem(item)))
                "reasoning" -> into(Role.Assistant, listOf(decodeReasoningItem(item)))
                "function_call_output", "custom_tool_call_output" -> into(Role.Tool, listOf(ToolResultBlock(
                    callId = item.str("call_id") ?: "",
                    content = when (val o = item["output"]) {
                        is JsonPrimitive -> listOf(TextBlock(o.contentOrNull ?: ""))
                        is JsonArray -> decodeContent(o, "$p.output", d)
                        else -> emptyList()
                    },
                    metadata = ProviderMetadata.EMPTY.withExtra(F, item, setOf("type", "call_id", "output"))
                        .let { m -> if (type == "custom_tool_call_output") m.with(NS, "custom", JsonPrimitive(true)) else m },
                )))
                "item_reference" -> into(Role.Assistant, listOf(RawBlock(type, item, F)))
                null -> d.warn(DiagnosticCodes.MALFORMED_INPUT, p, "item without type ignored")
                else -> {
                    // 托管工具的调用与输出：调用归 assistant，*_output 归工具结果一侧
                    val r = if (type.endsWith("_output") || type == "mcp_approval_response") Role.Tool else Role.Assistant
                    into(r, listOf(HostedToolBlock(type, item, F)))
                }
            }
        }
        flush()
    }

    private fun Block.withMeta(m: ProviderMetadata): Block = when (this) {
        is TextBlock -> copy(metadata = m)
        is RefusalBlock -> copy(metadata = m)
        is ImageBlock -> copy(metadata = m)
        is FileBlock -> copy(metadata = m)
        else -> this
    }

    private fun decodeCallItem(item: JsonObject): ToolCallBlock {
        var meta = ProviderMetadata.EMPTY.withExtra(F, item, setOf("type", "id", "call_id", "name", "arguments", "input"))
        item.str("id")?.let { meta = meta.with(NS, ITEM_ID, it) }
        return if (item.str("type") == "custom_tool_call") ToolCallBlock(item.str("call_id") ?: "", item.str("name") ?: "", item.str("input") ?: "", ToolKind.Custom, meta)
        else ToolCallBlock(item.str("call_id") ?: "", item.str("name") ?: "", item.str("arguments") ?: "", ToolKind.Function, meta)
    }

    private fun decodeReasoningItem(item: JsonObject): ReasoningBlock {
        var meta = ProviderMetadata.EMPTY.withExtra(F, item, setOf("type", "id", "summary", "content", "encrypted_content"))
        item.str("id")?.let { meta = meta.with(NS, ITEM_ID, it) }
        return ReasoningBlock(
            text = item.arr("content")?.objects()?.mapNotNull { it.str("text") }?.joinToString("") ?: "",
            encryptedContent = item.str("encrypted_content"),
            summary = item.arr("summary")?.objects()?.mapNotNull { it.str("text") } ?: emptyList(),
            metadata = meta,
        )
    }

    private fun decodeContent(content: JsonElement?, path: String, d: Diagnostics): List<Block> = when (content) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(content.contentOrNull?.let { TextBlock(it) })
        is JsonArray -> content.objects().mapIndexedNotNull { i, part -> decodePart(part, "$path[$i]", d) }
        is JsonObject -> listOfNotNull(decodePart(content, path, d))
    }

    private fun decodePart(p: JsonObject, path: String, d: Diagnostics): Block? = when (p.str("type")) {
        "input_text", "output_text", "text" -> TextBlock(p.str("text") ?: "", p.arr("annotations")?.objects() ?: emptyList(),
            ProviderMetadata.EMPTY.withExtra(F, p, setOf("type", "text", "annotations")).let { m -> if (p.str("type") == "output_text") m else m })
        "refusal" -> RefusalBlock(p.str("refusal") ?: "")
        "input_image" -> {
            val url = p.str("image_url")
            val fileId = p.str("file_id")
            when {
                url != null -> ImageBlock(DataUrl.toSource(url), p.str("detail"))
                fileId != null -> ImageBlock(MediaSource.FileRef(fileId), p.str("detail"))
                else -> null
            }
        }
        "input_file" -> {
            val fileId = p.str("file_id")
            val data = p.str("file_data")
            val url = p.str("file_url")
            when {
                fileId != null -> FileBlock(MediaSource.FileRef(fileId), p.str("filename"))
                data != null -> FileBlock(DataUrl.toSource(data, "application/pdf"), p.str("filename"))
                url != null -> FileBlock(MediaSource.Url(url), p.str("filename"))
                else -> null
            }
        }
        "input_audio" -> p.obj("input_audio")?.let { a -> FileBlock(MediaSource.Base64(a.str("data") ?: "", a.str("format")?.let { "audio/$it" })) }
        else -> {
            d.warn(DiagnosticCodes.MALFORMED_INPUT, path, "unsupported content part '${p.str("type")}' ignored")
            null
        }
    }

    private fun decodeTool(t: JsonObject): ToolSpec = when (t.str("type")) {
        // Responses 的 function 默认 strict=true（与 Chat 相反），解码时显式化，跨协议才不会改变语义
        "function" -> FunctionTool(t.str("name") ?: "", t.str("description"), t["parameters"], t.bool("strict") ?: true,
            ProviderMetadata.EMPTY.withExtra(F, t, setOf("type", "name", "description", "parameters", "strict")))
        "custom" -> CustomTool(t.str("name") ?: "", t.str("description"), t["format"],
            ProviderMetadata.EMPTY.withExtra(F, t, setOf("type", "name", "description", "format")))
        else -> HostedTool(t.str("type") ?: "", t, F)
    }

    private fun decodeToolChoice(e: JsonElement): ToolChoice? = when (e) {
        is JsonPrimitive -> when (e.contentOrNull) {
            "auto" -> ToolChoice.Auto
            "none" -> ToolChoice.None
            "required" -> ToolChoice.Required
            else -> null
        }
        is JsonObject -> if (e.str("type") == "function" || e.str("type") == "custom") e.str("name")?.let { ToolChoice.Named(it) } else null
        else -> null
    }

    private fun decodeFormat(f: JsonObject): ResponseFormat? = when (f.str("type")) {
        "text" -> ResponseFormat.Text
        "json_object" -> ResponseFormat.JsonObject
        "json_schema" -> ResponseFormat.JsonSchema(f.str("name") ?: "response", f["schema"] ?: JsonObject(emptyMap()), f.bool("strict"), f.str("description"))
        else -> null
    }

    private fun textOf(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonArray -> e.objects().mapNotNull { it.str("text") }.joinToString("")
        else -> ""
    }

    override fun encodeRequest(request: ChatRequest, diagnostics: Diagnostics): JsonObject = tracking {
        val d = diagnostics
        val o = request.options
        put("model", request.model)
        val (fieldInstructions, inputInstructions) = request.instructions.partition { it.metadata[NS, INSTRUCTIONS_FIELD] != null }
        if (fieldInstructions.isNotEmpty()) put("instructions", fieldInstructions.joinToString("\n\n") { it.text })
        val input = mutableListOf<JsonObject>()
        inputInstructions.forEach { ins ->
            input += tracking {
                put("type", "message")
                put("role", if (ins.role == Role.Developer) "developer" else "system")
                put("content", ins.text)
                putExtras(ins.metadata, F)
            }
        }
        request.messages.forEachIndexed { i, m -> input += encodeMessageItems(m, "messages[$i]", d) }
        put("input", JsonArray(input))
        val tools = request.tools.mapIndexedNotNull { i, t -> encodeTool(t, "tools[$i]", d) }
        if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        (request.metadata[NS, "toolChoice"] ?: o.toolChoice?.let(::encodeToolChoice))?.let { put("tool_choice", it) }
        put("parallel_tool_calls", o.parallelToolCalls)
        put("temperature", o.temperature)
        put("top_p", o.topP)
        put("max_output_tokens", o.maxOutputTokens)
        val reasoningExtra = request.metadata[NS, "reasoningExtra"] as? JsonObject
        o.reasoning?.let { r ->
            val effort = r.effort ?: when {
                r.mode == ReasoningMode.Disabled -> null
                r.budgetTokens != null -> Reasoning.effortForBudget(r.budgetTokens)
                r.mode != null -> "medium"
                else -> null
            }
            if (effort != null || reasoningExtra != null || r.includeThoughts == true) put("reasoning", tracking {
                put("effort", effort)
                reasoningExtra?.forEach { (k, v) -> if (k !in keys) put(k, v) }
                if (r.includeThoughts == true && "summary" !in keys) put("summary", "auto")
            })
        } ?: reasoningExtra?.let { put("reasoning", it) }
        val textExtra = request.metadata[NS, "textExtra"] as? JsonObject
        val format = o.responseFormat?.let(::encodeFormat)
        if (format != null || textExtra != null) put("text", JsonObject((textExtra ?: JsonObject(emptyMap())) + listOfNotNull(format?.let { "format" to it })))
        put("user", o.user)
        if (request.stream) put("stream", true)
        if (o.topK != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "top_k", "Responses has no top_k")
        if (o.stop.isNotEmpty()) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "stop", "Responses has no stop sequences")
        if (o.seed != null) d.warn(DiagnosticCodes.UNSUPPORTED_OPTION, "seed", "Responses has no seed")
        if ((o.candidateCount ?: 1) > 1) d.error(DiagnosticCodes.N_GT_1_UNSUPPORTED, "n", "Responses returns a single output")
        putExtras(request.metadata, F)
    }

    private fun encodeMessageItems(m: Message, path: String, d: Diagnostics): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        when (m.role) {
            Role.System, Role.Developer -> items += tracking {
                put("type", "message")
                put("role", if (m.role == Role.Developer) "developer" else "system")
                put("content", m.content.plainText("\n"))
                putExtras(m.metadata, F)
            }
            Role.User, Role.Tool -> {
                val pending = mutableListOf<Block>()
                fun flushUser() {
                    if (pending.isEmpty()) return
                    items += tracking {
                        put("type", "message"); put("role", "user")
                        put("content", JsonArray(pending.mapIndexedNotNull { j, b -> encodeInputPart(b, "$path.content[$j]", d) }))
                        putExtras(m.metadata, F)
                    }
                    pending.clear()
                }
                m.content.forEachIndexed { j, b ->
                    when (b) {
                        is ToolResultBlock -> { flushUser(); items += encodeToolOutput(b, "$path.content[$j]", d) }
                        is HostedToolBlock -> { flushUser(); encodeHosted(b, "$path.content[$j]", d)?.let { items += it } }
                        else -> pending += b
                    }
                }
                flushUser()
            }
            Role.Assistant -> items += encodeAssistantItems(m.content, path, d)
        }
        return items
    }

    /** assistant 块 → output item：同一 item ID（或相邻且无 ID）的文本合成一个 message item。 */
    internal fun encodeAssistantItems(blocks: List<Block>, path: String, d: Diagnostics, forOutput: Boolean = false): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        val texts = mutableListOf<Block>()
        var textItemId: String? = null
        var counter = 0
        fun flushText() {
            if (texts.isEmpty()) return
            items += tracking {
                put("type", "message")
                put("role", "assistant")
                put("id", textItemId ?: if (forOutput) "msg_${counter++}" else null)
                if (forOutput) put("status", "completed")
                put("content", JsonArray(texts.map { b ->
                    when (b) {
                        is RefusalBlock -> buildJsonObject { put("type", "refusal"); put("refusal", b.text) }
                        is TextBlock -> tracking {
                            put("type", "output_text"); put("text", b.text)
                            put("annotations", JsonArray(if (b.citations.all { it.str("type")?.let { t -> t.endsWith("_citation") || t == "file_path" } == true }) b.citations else emptyList()))
                            putExtras(b.metadata, F)
                        }
                        else -> JsonObject(emptyMap())
                    }
                }))
            }
            texts.clear(); textItemId = null
        }
        blocks.forEachIndexed { i, b ->
            val p = "$path.content[$i]"
            when (b) {
                is TextBlock, is RefusalBlock -> {
                    val id = b.metadata.string(NS, ITEM_ID)
                    if (texts.isNotEmpty() && id != textItemId) flushText()
                    textItemId = id
                    texts += b
                }
                is ReasoningBlock -> {
                    flushText()
                    if (b.signature != null || b.redacted) {
                        d.warn(DiagnosticCodes.REASONING_DROPPED, p, "Anthropic thinking cannot be sent as Responses reasoning")
                    } else if (b.encryptedContent != null || b.metadata.string(NS, ITEM_ID) != null || forOutput) {
                        items += tracking {
                            put("type", "reasoning")
                            put("id", b.metadata.string(NS, ITEM_ID) ?: if (forOutput) "rs_${counter++}" else null)
                            val summary = b.summary.ifEmpty { if (b.text.isNotEmpty() && b.metadata.string(NS, ITEM_ID) == null) listOf(b.text) else emptyList() }
                            put("summary", JsonArray(summary.map { buildJsonObject { put("type", "summary_text"); put("text", it) } }))
                            if (b.text.isNotEmpty() && b.metadata.string(NS, ITEM_ID) != null) {
                                put("content", buildJsonArray { add(buildJsonObject { put("type", "reasoning_text"); put("text", b.text) }) })
                            }
                            put("encrypted_content", b.encryptedContent)
                            putExtras(b.metadata, F)
                        }
                    } else {
                        // 没有 item ID 与加密内容的推理（来自其他协议），Responses 输入不接受
                        d.warn(DiagnosticCodes.REASONING_DROPPED, p, "reasoning without a Responses item cannot be sent")
                    }
                }
                is ToolCallBlock -> {
                    flushText()
                    items += tracking {
                        put("type", if (b.kind == ToolKind.Custom) "custom_tool_call" else "function_call")
                        put("id", b.metadata.string(NS, ITEM_ID) ?: if (forOutput) "fc_${counter++}" else null)
                        put("call_id", b.id)
                        put("name", b.name)
                        if (b.kind == ToolKind.Custom) put("input", b.arguments) else put("arguments", b.arguments)
                        if (forOutput) put("status", "completed")
                        putExtras(b.metadata, F)
                    }
                }
                is HostedToolBlock -> { flushText(); encodeHosted(b, p, d)?.let { items += it } }
                is RawBlock -> {
                    flushText()
                    if (b.sourceFormat == F) items += b.raw
                    else d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, p, "'${b.type}' block from ${b.sourceFormat.id} dropped")
                }
                else -> d.warn(DiagnosticCodes.MALFORMED_INPUT, p, "${b::class.simpleName} cannot appear in assistant output")
            }
        }
        flushText()
        return items
    }

    private fun encodeHosted(b: HostedToolBlock, path: String, d: Diagnostics): JsonObject? =
        if (b.sourceFormat == F) b.raw else {
            d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool activity '${b.type}' from ${b.sourceFormat.id} dropped")
            null
        }

    private fun encodeToolOutput(r: ToolResultBlock, path: String, d: Diagnostics): JsonObject = tracking {
        put("type", if (r.metadata[NS, "custom"] != null) "custom_tool_call_output" else "function_call_output")
        put("call_id", r.callId)
        val onlyText = r.content.all { it is TextBlock }
        put("output", if (onlyText) JsonPrimitive(r.content.plainText("\n"))
            else JsonArray(r.content.mapIndexedNotNull { j, b -> encodeInputPart(b, "$path.content[$j]", d) }))
        if (r.isError) d.warn("tool_error_flag_dropped", path, "Responses tool outputs have no error flag")
        putExtras(r.metadata, F)
    }

    private fun encodeInputPart(b: Block, path: String, d: Diagnostics): JsonObject? {
        if (b.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
            d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, path, "cache_control has no Responses equivalent")
        }
        return when (b) {
            is TextBlock -> tracking { put("type", "input_text"); put("text", b.text); putExtras(b.metadata, F) }
            is ImageBlock -> tracking {
                put("type", "input_image")
                when (val s = b.source) {
                    is MediaSource.Url -> put("image_url", s.url)
                    is MediaSource.Base64 -> put("image_url", DataUrl.of(s))
                    is MediaSource.FileRef -> put("file_id", s.fileId)
                }
                put("detail", b.detail ?: "auto")
            }
            is FileBlock -> {
                val s = b.source
                if (s.mediaType?.startsWith("audio/") == true && s is MediaSource.Base64) buildJsonObject {
                    put("type", "input_audio")
                    put("input_audio", buildJsonObject { put("data", s.data); put("format", s.mediaType!!.removePrefix("audio/")) })
                } else tracking {
                    put("type", "input_file")
                    when (s) {
                        is MediaSource.FileRef -> put("file_id", s.fileId)
                        is MediaSource.Base64 -> { put("file_data", DataUrl.of(s)); put("filename", b.filename ?: "file") }
                        is MediaSource.Url -> put("file_url", s.url)
                    }
                    if (s !is MediaSource.Base64) put("filename", b.filename)
                }
            }
            is RefusalBlock -> buildJsonObject { put("type", "input_text"); put("text", b.text) }
            else -> {
                d.error(DiagnosticCodes.UNSUPPORTED_CONTENT, path, "${b::class.simpleName} cannot appear in Responses input content")
                null
            }
        }
    }

    private fun encodeTool(t: ToolSpec, path: String, d: Diagnostics): JsonObject? {
        if (t.metadata[ProviderMetadata.ANTHROPIC, ProviderMetadata.CACHE_CONTROL] != null) {
            d.warn(DiagnosticCodes.CACHE_CONTROL_DROPPED, path, "cache_control has no Responses equivalent")
        }
        return when (t) {
            is FunctionTool -> tracking {
                put("type", "function")
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parameters ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) })
                put("strict", t.strict ?: false)
                putExtras(t.metadata, F)
            }
            is CustomTool -> tracking {
                put("type", "custom"); put("name", t.name); put("description", t.description); put("format", t.format)
                putExtras(t.metadata, F)
            }
            is HostedTool -> if (t.sourceFormat == F) t.raw else {
                d.error(DiagnosticCodes.HOSTED_TOOL_OMITTED, path, "hosted tool '${t.type}' from ${t.sourceFormat.id} has no Responses equivalent")
                null
            }
        }
    }

    private fun encodeToolChoice(c: ToolChoice): JsonElement = when (c) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject { put("type", "function"); put("name", c.name) }
    }

    private fun encodeFormat(rf: ResponseFormat): JsonObject = when (rf) {
        ResponseFormat.Text -> buildJsonObject { put("type", "text") }
        ResponseFormat.JsonObject -> buildJsonObject { put("type", "json_object") }
        is ResponseFormat.JsonSchema -> buildJsonObject {
            put("type", "json_schema"); put("name", rf.name)
            rf.description?.let { put("description", it) }
            put("schema", rf.schema)
            rf.strict?.let { put("strict", it) }
        }
    }

    // ───────────────────────── 响应 ─────────────────────────

    override fun decodeResponse(body: JsonObject, diagnostics: Diagnostics): ChatResponse {
        val messages = mutableListOf<Message>()
        decodeItems(body.arr("output")?.objects() ?: emptyList(), "output", diagnostics, messages, null)
        val content = messages.flatMap { it.content }
        val status = body.str("status")
        val incomplete = body.obj("incomplete_details")?.str("reason")
        return ChatResponse(
            id = body.str("id"),
            model = body.str("model"),
            content = content,
            finishReason = finishReasonOf(status, incomplete, content.any { it is ToolCallBlock }),
            rawFinishReason = incomplete ?: status,
            usage = body.obj("usage")?.let(::decodeUsage),
            created = body.long("created_at"),
            metadata = ProviderMetadata.EMPTY.withExtra(F, body, setOf("id", "object", "model", "output", "status", "incomplete_details", "usage", "created_at")),
        )
    }

    internal fun finishReasonOf(status: String?, incomplete: String?, hasToolCalls: Boolean): FinishReason? = when (status) {
        "completed" -> if (hasToolCalls) FinishReason.ToolCalls else FinishReason.Stop
        "incomplete" -> if (incomplete == "content_filter") FinishReason.ContentFilter else FinishReason.Length
        "failed" -> FinishReason.Error
        null -> null
        else -> FinishReason.Other
    }

    private fun statusOf(reason: FinishReason?): Pair<String, String?> = when (reason) {
        FinishReason.Length -> "incomplete" to "max_output_tokens"
        FinishReason.ContentFilter, FinishReason.Refusal -> "incomplete" to "content_filter"
        FinishReason.Error -> "failed" to null
        else -> "completed" to null
    }

    override fun encodeResponse(response: ChatResponse, diagnostics: Diagnostics): JsonObject =
        responseObject(response.id, response.model, response.created,
            encodeAssistantItems(response.content, "content", diagnostics, forOutput = true),
            response.finishReason, response.usage, response.metadata)

    private fun responseObject(
        id: String?, model: String?, created: Long?, output: List<JsonObject>, reason: FinishReason?, usage: Usage?,
        metadata: ProviderMetadata, inProgress: Boolean = false,
    ): JsonObject = tracking {
        val (status, incomplete) = if (inProgress) "in_progress" to null else statusOf(reason)
        put("id", id ?: "resp_neton")
        put("object", "response")
        put("created_at", created ?: nowEpochSeconds())
        put("status", status)
        put("error", JsonNull)
        put("incomplete_details", incomplete?.let { buildJsonObject { put("reason", it) } } ?: JsonNull)
        put("model", model ?: "")
        put("output", JsonArray(output))
        put("usage", usage?.let(::encodeUsage) ?: JsonNull)
        putExtras(metadata, F)
    }

    internal fun decodeUsage(u: JsonObject): Usage = Usage(
        inputTokens = u.long("input_tokens"),
        outputTokens = u.long("output_tokens"),
        cacheReadTokens = u.obj("input_tokens_details")?.long("cached_tokens"),
        reasoningTokens = u.obj("output_tokens_details")?.long("reasoning_tokens"),
        completeness = UsageCompleteness.COMPLETE,
        raw = RawUsage(F, u),
    )

    internal fun encodeUsage(u: Usage): JsonObject {
        if (u.raw?.format == F) return u.raw.json
        return tracking {
            put("input_tokens", u.inputTokens ?: 0)
            put("input_tokens_details", buildJsonObject { put("cached_tokens", u.cacheReadTokens ?: 0) })
            put("output_tokens", u.outputTokens ?: 0)
            put("output_tokens_details", buildJsonObject { put("reasoning_tokens", u.reasoningTokens ?: 0) })
            if (u.inputTokens != null && u.outputTokens != null) put("total_tokens", u.inputTokens + u.outputTokens)
        }
    }

    // ───────────────────────── 错误 ─────────────────────────

    override fun encodeError(error: ProtocolError): JsonObject = OpenAiChatCodec.encodeError(error)

    override fun decodeError(status: Int, body: String): ProtocolError =
        OpenAiChatCodec.decodeError(status, body).copy(sourceFormat = F)

    // ───────────────────────── 流式 ─────────────────────────

    override fun streamDecoder(diagnostics: Diagnostics): StreamDecoder = ResponsesStreamDecoder(diagnostics)
    override fun streamEncoder(diagnostics: Diagnostics): StreamEncoder = ResponsesStreamEncoder(diagnostics)

    private class ResponsesStreamDecoder(private val d: Diagnostics) : StreamDecoder {
        private val seq = BlockSequencer()
        override var completed = false
            private set
        private var started = false
        private var hasToolCalls = false
        private val itemTypes = HashMap<Int, String>()
        private val itemIds = HashMap<Int, String>()

        override fun decode(event: SseEvent): List<StreamEvent> {
            val data = event.data.trim()
            if (data.isEmpty() || data == "[DONE]") return emptyList()
            val obj = J.parseObject(data) ?: return malformed(seq, d, WireFormat.OPENAI_RESPONSES).also { completed = true }
            val out = mutableListOf<StreamEvent>()
            val type = obj.str("type") ?: event.event
            val oi = obj.int("output_index") ?: 0
            when (type) {
                "response.created" -> start(obj.obj("response"), out)
                "response.output_item.added" -> {
                    start(null, out)
                    val item = obj.obj("item") ?: return out
                    val itemType = item.str("type") ?: ""
                    itemTypes[oi] = itemType
                    item.str("id")?.let { itemIds[oi] = it }
                    when (itemType) {
                        "message" -> {}
                        "reasoning" -> seq.ensure("rs:$oi", out) { ReasoningBlock(metadata = idMeta(oi)) }
                        "function_call", "custom_tool_call" -> {
                            hasToolCalls = true
                            seq.ensure("fc:$oi", out) {
                                ToolCallBlock(item.str("call_id") ?: "", item.str("name") ?: "",
                                    item.str("arguments") ?: item.str("input") ?: "",
                                    if (itemType == "custom_tool_call") ToolKind.Custom else ToolKind.Function, idMeta(oi))
                            }
                        }
                        else -> seq.ensure("item:$oi", out) { HostedToolBlock(itemType, item, WireFormat.OPENAI_RESPONSES) }
                    }
                }
                "response.content_part.added" -> {
                    val part = obj.obj("part")
                    val ci = obj.int("content_index") ?: 0
                    if (part?.str("type") == "refusal") seq.ensure("refusal:$oi:$ci", out) { RefusalBlock("", idMeta(oi)) }
                    else seq.ensure("text:$oi:$ci", out) { TextBlock("", metadata = idMeta(oi)) }
                }
                "response.output_text.delta" -> {
                    val ci = obj.int("content_index") ?: 0
                    seq.delta("text:$oi:$ci", Delta.Text(obj.str("delta") ?: ""), out) { TextBlock("", metadata = idMeta(oi)) }
                }
                "response.output_text.annotation.added" -> obj.obj("annotation")?.let { a ->
                    val ci = obj.int("content_index") ?: 0
                    seq.delta("text:$oi:$ci", Delta.Citation(a), out) { TextBlock("", metadata = idMeta(oi)) }
                }
                "response.refusal.delta" -> {
                    val ci = obj.int("content_index") ?: 0
                    seq.delta("refusal:$oi:$ci", Delta.Text(obj.str("delta") ?: ""), out) { RefusalBlock("", idMeta(oi)) }
                }
                "response.reasoning_summary_text.delta" ->
                    seq.delta("rs:$oi", Delta.ReasoningSummary(obj.str("delta") ?: ""), out) { ReasoningBlock(metadata = idMeta(oi)) }
                "response.reasoning_text.delta" ->
                    seq.delta("rs:$oi", Delta.Reasoning(obj.str("delta") ?: ""), out) { ReasoningBlock(metadata = idMeta(oi)) }
                "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" ->
                    seq.delta("fc:$oi", Delta.Arguments(obj.str("delta") ?: ""), out) { ToolCallBlock("", "", "") }
                "response.content_part.done" -> seq.close(out)
                "response.output_item.done" -> {
                    val item = obj.obj("item")
                    var meta = idMeta(oi)
                    item?.str("encrypted_content")?.let { meta = meta.with(NS, ENCRYPTED, it) }
                    if (item != null && itemTypes[oi] !in setOf("message", "reasoning", "function_call", "custom_tool_call")) meta = meta.with(NS, FINAL_ITEM, item)
                    if (itemTypes[oi] == "message") seq.close(out) else if (seq.isOpen) seq.close(out, meta)
                }
                "response.completed", "response.incomplete", "response.failed" -> {
                    seq.close(out)
                    val r = obj.obj("response")
                    r?.obj("usage")?.let { out += StreamEvent.UsageUpdate(decodeUsage(it)) }
                    if (type == "response.failed") {
                        val err = r?.obj("error")
                        out += StreamEvent.Error(ProtocolError(ErrorKind.ServerError, err?.str("message") ?: "response failed",
                            code = err?.str("code"), rawBody = r?.toString(), sourceFormat = WireFormat.OPENAI_RESPONSES))
                    } else {
                        val incomplete = r?.obj("incomplete_details")?.str("reason")
                        val status = r?.str("status") ?: if (type == "response.incomplete") "incomplete" else "completed"
                        finishReasonOf(status, incomplete, hasToolCalls)?.let { out += StreamEvent.Finish(it, incomplete ?: status) }
                    }
                    completed = true
                }
                "error" -> {
                    seq.close(out)
                    out += StreamEvent.Error(ProtocolError(ErrorKind.ServerError, obj.str("message") ?: "stream error",
                        code = obj.str("code"), rawBody = obj.toString(), sourceFormat = WireFormat.OPENAI_RESPONSES))
                    completed = true
                }
            }
            return out
        }

        private fun idMeta(oi: Int): ProviderMetadata =
            itemIds[oi]?.let { ProviderMetadata.EMPTY.with(NS, ITEM_ID, it) } ?: ProviderMetadata.EMPTY

        private fun start(response: JsonObject?, out: MutableList<StreamEvent>) {
            if (started) return
            started = true
            out += StreamEvent.MessageStart(response?.str("id"), response?.str("model"), response?.long("created_at"))
        }

        override fun finish(): List<StreamEvent> {
            val out = mutableListOf<StreamEvent>()
            seq.close(out)
            return out
        }
    }

    private class ResponsesStreamEncoder(private val d: Diagnostics) : StreamEncoder {
        private var id = "resp_neton"
        private var model = ""
        private var created: Long? = null
        private var started = false
        private var done = false
        private var seqNo = 0
        private var outputIndex = 0
        private var itemCounter = 0
        private val finalItems = mutableListOf<JsonObject>()
        private var usage: Usage? = null
        private var finish: StreamEvent.Finish? = null

        // 打开中的 message item（可容纳多个 content part）
        private var msgItemId: String? = null
        private var msgOutputIndex = -1
        private val msgParts = mutableListOf<JsonObject>()

        private class Open(val ir: Int, val kind: Block, val itemId: String, val outputIndex: Int, val contentIndex: Int) {
            val text = StringBuilder()
            val summary = StringBuilder()
            val annotations = mutableListOf<JsonObject>()
        }
        private var open: Open? = null

        private val sequential = neton.ai.protocol.stream.SequentialBlocks()

        override fun encode(event: StreamEvent): String {
            if (done) return ""
            val sb = StringBuilder()
            for (e in sequential.accept(event)) encodeOne(e, sb)
            return sb.toString()
        }

        private fun encodeOne(event: StreamEvent, sb: StringBuilder) {
            if (done) return
            when (event) {
                is StreamEvent.MessageStart -> {
                    event.id?.let { id = it }
                    event.model?.let { model = it }
                    created = event.created
                    ensureStarted(sb)
                }
                is StreamEvent.BlockStart -> { ensureStarted(sb); startBlock(event.index, event.block, sb) }
                is StreamEvent.BlockDelta -> delta(event, sb)
                is StreamEvent.BlockStop -> if (open?.ir == event.index) stopBlock(sb, event.metadata)
                is StreamEvent.UsageUpdate -> usage = usage?.mergedWith(event.usage) ?: event.usage
                is StreamEvent.Finish -> finish = event
                is StreamEvent.Error -> {
                    ensureStarted(sb)
                    emit(sb, "error", buildJsonObject {
                        put("type", "error")
                        put("code", event.error.code ?: "server_error")
                        put("message", event.error.message)
                        put("param", JsonNull)
                    })
                    done = true
                }
            }
        }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            val r = responseObject(id, model, created ?: nowEpochSeconds().also { created = it }, emptyList(), null, null, ProviderMetadata.EMPTY, inProgress = true)
            emit(sb, "response.created", buildJsonObject { put("type", "response.created"); put("response", r) })
            emit(sb, "response.in_progress", buildJsonObject { put("type", "response.in_progress"); put("response", r) })
        }

        private fun startBlock(ir: Int, b: Block, sb: StringBuilder) {
            stopBlock(sb, ProviderMetadata.EMPTY)
            when (b) {
                is TextBlock, is RefusalBlock -> {
                    if (msgItemId == null) {
                        msgItemId = b.metadata.string(NS, ITEM_ID) ?: "msg_${id}_${itemCounter++}"
                        msgOutputIndex = outputIndex++
                        emit(sb, "response.output_item.added", buildJsonObject {
                            put("type", "response.output_item.added"); put("output_index", msgOutputIndex)
                            put("item", buildJsonObject {
                                put("id", msgItemId); put("type", "message"); put("status", "in_progress"); put("role", "assistant")
                                put("content", buildJsonArray { })
                            })
                        })
                    }
                    val o = Open(ir, b, msgItemId!!, msgOutputIndex, msgParts.size)
                    open = o
                    val part = if (b is RefusalBlock) buildJsonObject { put("type", "refusal"); put("refusal", "") }
                        else buildJsonObject { put("type", "output_text"); put("text", ""); put("annotations", buildJsonArray { }) }
                    emit(sb, "response.content_part.added", buildJsonObject {
                        put("type", "response.content_part.added"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("content_index", o.contentIndex); put("part", part)
                    })
                    val initial = (b as? TextBlock)?.text ?: (b as? RefusalBlock)?.text ?: ""
                    if (initial.isNotEmpty()) delta(StreamEvent.BlockDelta(ir, Delta.Text(initial)), sb)
                }
                is ReasoningBlock -> {
                    closeMessage(sb)
                    if (b.signature != null || b.redacted) {
                        d.warn(DiagnosticCodes.REASONING_DROPPED, "stream", "Anthropic thinking cannot be sent as Responses reasoning")
                    }
                    val itemId = b.metadata.string(NS, ITEM_ID) ?: "rs_${id}_${itemCounter++}"
                    val o = Open(ir, b, itemId, outputIndex++, 0)
                    open = o
                    emit(sb, "response.output_item.added", buildJsonObject {
                        put("type", "response.output_item.added"); put("output_index", o.outputIndex)
                        put("item", buildJsonObject { put("id", itemId); put("type", "reasoning"); put("summary", buildJsonArray { }) })
                    })
                    if (b.text.isNotEmpty()) delta(StreamEvent.BlockDelta(ir, Delta.Reasoning(b.text)), sb)
                }
                is ToolCallBlock -> {
                    closeMessage(sb)
                    val itemId = b.metadata.string(NS, ITEM_ID) ?: "fc_${id}_${itemCounter++}"
                    val o = Open(ir, b, itemId, outputIndex++, 0)
                    open = o
                    val custom = b.kind == ToolKind.Custom
                    emit(sb, "response.output_item.added", buildJsonObject {
                        put("type", "response.output_item.added"); put("output_index", o.outputIndex)
                        put("item", buildJsonObject {
                            put("id", itemId); put("type", if (custom) "custom_tool_call" else "function_call"); put("status", "in_progress")
                            put("call_id", b.id); put("name", b.name); put(if (custom) "input" else "arguments", "")
                        })
                    })
                    if (b.arguments.isNotEmpty()) delta(StreamEvent.BlockDelta(ir, Delta.Arguments(b.arguments)), sb)
                }
                is HostedToolBlock -> {
                    closeMessage(sb)
                    if (b.sourceFormat != WireFormat.OPENAI_RESPONSES) {
                        d.warn(DiagnosticCodes.HOSTED_TOOL_OMITTED, "stream", "hosted tool activity '${b.type}' omitted")
                        return
                    }
                    val o = Open(ir, b, b.raw.str("id") ?: "item_${itemCounter++}", outputIndex++, 0)
                    open = o
                    emit(sb, "response.output_item.added", buildJsonObject {
                        put("type", "response.output_item.added"); put("output_index", o.outputIndex); put("item", b.raw)
                    })
                }
                else -> {}
            }
        }

        private fun delta(event: StreamEvent.BlockDelta, sb: StringBuilder) {
            val o = open?.takeIf { it.ir == event.index } ?: return
            when (val dl = event.delta) {
                is Delta.Text -> {
                    o.text.append(dl.text)
                    val refusal = o.kind is RefusalBlock
                    emit(sb, if (refusal) "response.refusal.delta" else "response.output_text.delta", buildJsonObject {
                        put("type", if (refusal) "response.refusal.delta" else "response.output_text.delta")
                        put("item_id", o.itemId); put("output_index", o.outputIndex); put("content_index", o.contentIndex)
                        put("delta", dl.text)
                        if (!refusal) put("logprobs", buildJsonArray { })
                    })
                }
                is Delta.Reasoning, is Delta.ReasoningSummary -> {
                    val text = (dl as? Delta.Reasoning)?.text ?: (dl as Delta.ReasoningSummary).text
                    if (o.summary.isEmpty()) emit(sb, "response.reasoning_summary_part.added", buildJsonObject {
                        put("type", "response.reasoning_summary_part.added"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("summary_index", 0); put("part", buildJsonObject { put("type", "summary_text"); put("text", "") })
                    })
                    o.summary.append(text)
                    emit(sb, "response.reasoning_summary_text.delta", buildJsonObject {
                        put("type", "response.reasoning_summary_text.delta"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("summary_index", 0); put("delta", text)
                    })
                }
                is Delta.Arguments -> {
                    o.text.append(dl.json)
                    val custom = (o.kind as? ToolCallBlock)?.kind == ToolKind.Custom
                    val name = if (custom) "response.custom_tool_call_input.delta" else "response.function_call_arguments.delta"
                    emit(sb, name, buildJsonObject {
                        put("type", name); put("item_id", o.itemId); put("output_index", o.outputIndex); put("delta", dl.json)
                    })
                }
                is Delta.Citation -> {
                    o.annotations += dl.citation
                    emit(sb, "response.output_text.annotation.added", buildJsonObject {
                        put("type", "response.output_text.annotation.added"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("content_index", o.contentIndex); put("annotation_index", o.annotations.size - 1); put("annotation", dl.citation)
                    })
                }
                is Delta.Signature -> {}
            }
        }

        private fun stopBlock(sb: StringBuilder, meta: ProviderMetadata) {
            val o = open ?: return
            open = null
            when (val b = o.kind) {
                is TextBlock, is RefusalBlock -> {
                    val refusal = b is RefusalBlock
                    val part = if (refusal) buildJsonObject { put("type", "refusal"); put("refusal", o.text.toString()) }
                        else buildJsonObject { put("type", "output_text"); put("text", o.text.toString()); put("annotations", JsonArray(o.annotations)) }
                    emit(sb, if (refusal) "response.refusal.done" else "response.output_text.done", buildJsonObject {
                        put("type", if (refusal) "response.refusal.done" else "response.output_text.done")
                        put("item_id", o.itemId); put("output_index", o.outputIndex); put("content_index", o.contentIndex)
                        put(if (refusal) "refusal" else "text", o.text.toString())
                    })
                    emit(sb, "response.content_part.done", buildJsonObject {
                        put("type", "response.content_part.done"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("content_index", o.contentIndex); put("part", part)
                    })
                    msgParts += part
                }
                is ReasoningBlock -> {
                    if (o.summary.isNotEmpty()) {
                        emit(sb, "response.reasoning_summary_text.done", buildJsonObject {
                            put("type", "response.reasoning_summary_text.done"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                            put("summary_index", 0); put("text", o.summary.toString())
                        })
                        emit(sb, "response.reasoning_summary_part.done", buildJsonObject {
                            put("type", "response.reasoning_summary_part.done"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                            put("summary_index", 0); put("part", buildJsonObject { put("type", "summary_text"); put("text", o.summary.toString()) })
                        })
                    }
                    val encrypted = meta.string(NS, ENCRYPTED) ?: b.encryptedContent
                    itemDone(sb, o.outputIndex, tracking {
                        put("id", o.itemId); put("type", "reasoning")
                        put("summary", JsonArray(if (o.summary.isEmpty()) emptyList() else listOf(buildJsonObject { put("type", "summary_text"); put("text", o.summary.toString()) })))
                        put("encrypted_content", encrypted)
                    })
                }
                is ToolCallBlock -> {
                    val custom = b.kind == ToolKind.Custom
                    if (!custom) emit(sb, "response.function_call_arguments.done", buildJsonObject {
                        put("type", "response.function_call_arguments.done"); put("item_id", o.itemId); put("output_index", o.outputIndex)
                        put("arguments", o.text.toString())
                    })
                    itemDone(sb, o.outputIndex, buildJsonObject {
                        put("id", o.itemId); put("type", if (custom) "custom_tool_call" else "function_call"); put("status", "completed")
                        put("call_id", b.id); put("name", b.name); put(if (custom) "input" else "arguments", o.text.toString())
                    })
                }
                is HostedToolBlock -> itemDone(sb, o.outputIndex, meta[NS, FINAL_ITEM] as? JsonObject ?: b.raw)
                else -> {}
            }
        }

        private fun closeMessage(sb: StringBuilder) {
            val itemId = msgItemId ?: return
            itemDone(sb, msgOutputIndex, buildJsonObject {
                put("id", itemId); put("type", "message"); put("status", "completed"); put("role", "assistant")
                put("content", JsonArray(msgParts.toList()))
            })
            msgItemId = null
            msgParts.clear()
        }

        private fun itemDone(sb: StringBuilder, outputIndex: Int, item: JsonObject) {
            finalItems += item
            emit(sb, "response.output_item.done", buildJsonObject {
                put("type", "response.output_item.done"); put("output_index", outputIndex); put("item", item)
            })
        }

        private fun emit(sb: StringBuilder, name: String, data: JsonObject) {
            sb.append(SseWriter.event(name, JsonObject(data + ("sequence_number" to JsonPrimitive(seqNo++))).toString()))
        }

        override fun finish(truncated: Boolean): String {
            if (done) return ""
            done = true
            if (truncated) return ""
            val sb = StringBuilder()
            done = false
            for (e in sequential.flush()) encodeOne(e, sb)
            done = true
            ensureStarted(sb)
            stopBlock(sb, ProviderMetadata.EMPTY)
            closeMessage(sb)
            val f = finish
            val reason = f?.reason ?: FinishReason.Stop
            val r = responseObject(id, model, created, finalItems, reason, usage, ProviderMetadata.EMPTY)
            val name = if (r.str("status") == "incomplete") "response.incomplete" else if (r.str("status") == "failed") "response.failed" else "response.completed"
            emit(sb, name, buildJsonObject { put("type", name); put("response", r) })
            return sb.toString()
        }
    }
}
