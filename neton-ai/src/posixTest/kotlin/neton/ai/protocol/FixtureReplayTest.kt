package neton.ai.protocol

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.codec.ProtocolCodecs
import neton.ai.protocol.ir.ChatResponse
import neton.ai.protocol.ir.FinishReason
import neton.ai.protocol.ir.ReasoningBlock
import neton.ai.protocol.ir.Delta
import neton.ai.protocol.ir.StreamEvent
import neton.ai.protocol.ir.TextBlock
import neton.ai.protocol.ir.ToolCallBlock
import neton.ai.protocol.ir.ToolKind
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.transport.SseEvent
import neton.ai.protocol.transport.SseFramer
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import platform.posix.opendir
import platform.posix.readdir
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 真实上游回放：Vercel AI SDK 录制的厂商响应（fixtures/vercel-ai，Apache-2.0，见其中 NOTICE）。
 *
 * 每个样本：用来源协议解码 → 编码成全部五种协议 → 再解码，检查
 * - 解码不抛异常，流有终止标记（录制本身就是错误或截断的样本除外）；
 * - 跨协议往返后文本与工具调用（名称 + 参数 JSON）不丢、不变。
 *
 * 失败汇总后一次报告，方便看清是哪个厂商的哪种形状没处理好。
 */
@OptIn(ExperimentalForeignApi::class)
class FixtureReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val sets = mapOf(
        "openai-chat" to WireFormat.OPENAI_CHAT,
        "openai-responses" to WireFormat.OPENAI_RESPONSES,
        "anthropic" to WireFormat.ANTHROPIC,
        "gemini" to WireFormat.GEMINI,
        "bedrock" to WireFormat.BEDROCK_CONVERSE,
    )

    /** 录制内容本身就是错误、中途失败或畸形序列的样本：不要求有终止标记。 */
    private val incomplete = Regex("error|fallback-mid-output|spliced|missing-terminal|429")

    private data class Tool(val name: String, val arguments: String, val custom: Boolean)
    private data class Summary(
        val text: String,
        val tools: List<Tool>,
        val reasoning: String = "",
        val finish: FinishReason? = null,
        /** 输入、输出、缓存读取 token（取最后一次报告的值）。 */
        val usage: Triple<Long?, Long?, Long?> = Triple(null, null, null),
    )

    /**
     * 目标协议表达不了的部分按它能表达的形状比较（都是有意的、有诊断或协议使然的损耗）：
     * - 没有 custom 工具的协议写成函数调用 `{"input": 原文}`；
     * - Anthropic 只有 refusal，其他协议只有 content_filter；
     * - Responses、Gemini 没有显式结束原因，有工具调用即推断为 ToolCalls；
     * - Responses、Anthropic 的用量字段是必填的，未知只能写 0。
     */
    private fun expectedIn(target: WireFormat, s: Summary): Summary {
        var out = s
        if (target !in setOf(WireFormat.OPENAI_CHAT, WireFormat.OPENAI_RESPONSES)) {
            out = out.copy(tools = out.tools.map { t -> if (!t.custom) t else Tool(t.name, normalize(buildJsonObject { put("input", t.arguments) }.toString()), false) })
        }
        out = out.copy(finish = when {
            target == WireFormat.ANTHROPIC && out.finish == FinishReason.ContentFilter -> FinishReason.Refusal
            target != WireFormat.ANTHROPIC && out.finish == FinishReason.Refusal -> FinishReason.ContentFilter
            target in setOf(WireFormat.OPENAI_RESPONSES, WireFormat.GEMINI) && out.finish == FinishReason.Stop && out.tools.isNotEmpty() -> FinishReason.ToolCalls
            else -> out.finish
        })
        return comparable(target, out)
    }

    private fun comparable(target: WireFormat, s: Summary): Summary {
        val zeroed = target in setOf(WireFormat.OPENAI_RESPONSES, WireFormat.ANTHROPIC)
        val u = if (zeroed) Triple(s.usage.first ?: 0, s.usage.second ?: 0, s.usage.third ?: 0) else s.usage
        return s.copy(tools = s.tools.map { it.copy(custom = false) }, usage = u)
    }

    @Test
    fun streamsReplayAndSurviveEveryTarget() {
        val failures = mutableListOf<String>()
        var count = 0
        forEachFixture(".chunks.txt") { dir, format, name, content ->
            count++
            try {
                val events = decodeStream(format, upstreamEvents(format, content))
                if (events.isEmpty()) { failures += "$dir/$name: no events"; return@forEachFixture }
                val failed = events.any { it is StreamEvent.Error }
                if (!failed && !incomplete.containsMatchIn(name) && events.none { it is StreamEvent.Finish }) {
                    failures += "$dir/$name: stream has no Finish"
                }
                val source = summarize(events)
                for (target in WireFormat.entries) {
                    val enc = ProtocolCodecs.of(target).streamEncoder()
                    val wire = buildString { events.forEach { append(enc.encode(it)) }; append(enc.finish(truncated = failed)) }
                    val f = SseFramer()
                    val again = summarize(decodeStream(target, f.feed(wire) + f.finish()))
                    val expected = expectedIn(target, source)
                    val actual = comparable(target, again)
                    if (!failed && actual != expected) failures += "$dir/$name → ${target.id}: ${diff(expected, actual)}"
                }
            } catch (e: Throwable) {
                failures += "$dir/$name: ${e::class.simpleName}: ${e.message}"
            }
        }
        report(count, failures)
    }

    @Test
    fun responsesReplayAndSurviveEveryTarget() {
        val failures = mutableListOf<String>()
        var count = 0
        forEachFixture(".json") { dir, format, name, content ->
            count++
            try {
                val body = json.parseToJsonElement(content).jsonObject
                val codec = ProtocolCodecs.of(format)
                if (isErrorBody(body)) {
                    codec.decodeError(400, content)
                    return@forEachFixture
                }
                val response = codec.decodeResponse(body)
                val source = summarize(response)
                for (target in WireFormat.entries) {
                    val t = ProtocolCodecs.of(target)
                    val again = summarize(t.decodeResponse(t.encodeResponse(response)))
                    val expected = expectedIn(target, source)
                    val actual = comparable(target, again)
                    if (actual != expected) failures += "$dir/$name → ${target.id}: ${diff(expected, actual)}"
                }
            } catch (e: Throwable) {
                failures += "$dir/$name: ${e::class.simpleName}: ${e.message}"
            }
        }
        report(count, failures)
    }

    // ───────────────────────── 样本 → 上游事件 ─────────────────────────

    /** 录制格式是一行一条 data 负载；按各协议的 SSE 习惯还原事件名。 */
    private fun upstreamEvents(format: WireFormat, content: String): List<SseEvent> {
        val lines = content.lines().filter { it.isNotBlank() }
        return when (format) {
            WireFormat.OPENAI_CHAT -> lines.map { SseEvent(null, it) } + SseEvent(null, "[DONE]")
            WireFormat.GEMINI -> lines.map { SseEvent(null, it) }
            WireFormat.ANTHROPIC, WireFormat.OPENAI_RESPONSES -> lines.map { line ->
                // 畸形的行原样交给解码器：它应当报告错误而不是崩溃
                val type = runCatching { json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
                SseEvent(type, line)
            }
            // Bedrock 的 event-stream 帧：事件名是唯一的顶层键，负载是它的值
            WireFormat.BEDROCK_CONVERSE -> lines.map { line ->
                val (k, v) = json.parseToJsonElement(line).jsonObject.entries.single()
                SseEvent(k, v.toString())
            }
        }
    }

    private fun decodeStream(format: WireFormat, events: List<SseEvent>): List<StreamEvent> {
        val d = ProtocolCodecs.of(format).streamDecoder()
        return events.flatMap { d.decode(it) } + d.finish()
    }

    private fun isErrorBody(o: JsonObject) = "error" in o && "choices" !in o && "candidates" !in o && "output" !in o && "content" !in o

    // ───────────────────────── 比较 ─────────────────────────

    private fun summarize(events: List<StreamEvent>): Summary {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var finish: FinishReason? = null
        var input: Long? = null; var output: Long? = null; var cacheRead: Long? = null
        val tools = LinkedHashMap<Int, Triple<String, StringBuilder, Boolean>>()
        for (e in events) when (e) {
            is StreamEvent.BlockStart -> (e.block as? ToolCallBlock)?.let { tools[e.index] = Triple(it.name, StringBuilder(it.arguments), it.kind == ToolKind.Custom) }
                ?: (e.block as? TextBlock)?.let { text.append(it.text) }
                ?: (e.block as? ReasoningBlock)?.let { reasoning.append(it.text.ifEmpty { it.summary.joinToString("\n") }) }
            is StreamEvent.BlockDelta -> when (val d = e.delta) {
                is Delta.Text -> text.append(d.text)
                is Delta.Arguments -> tools[e.index]?.second?.append(d.json)
                is Delta.Reasoning -> reasoning.append(d.text)
                is Delta.ReasoningSummary -> reasoning.append(d.text)
                else -> {}
            }
            is StreamEvent.Finish -> finish = e.reason
            is StreamEvent.UsageUpdate -> {
                e.usage.inputTokens?.let { input = it }
                e.usage.outputTokens?.let { output = it }
                e.usage.cacheReadTokens?.let { cacheRead = it }
            }
            else -> {}
        }
        return Summary(text.toString(), tools.entries.sortedBy { it.key }.map { (_, v) -> tool(v.first, v.second.toString(), v.third) },
            reasoning.toString(), finish, Triple(input, output, cacheRead))
    }

    private fun summarize(r: ChatResponse) = Summary(
        r.content.filterIsInstance<TextBlock>().joinToString("") { it.text },
        r.content.filterIsInstance<ToolCallBlock>().map { tool(it.name, it.arguments, it.kind == ToolKind.Custom) },
        r.content.filterIsInstance<ReasoningBlock>().joinToString("") { it.text.ifEmpty { it.summary.joinToString("\n") } },
        r.finishReason,
        Triple(r.usage?.inputTokens, r.usage?.outputTokens, r.usage?.cacheReadTokens),
    )

    private fun tool(name: String, args: String, custom: Boolean) = Tool(name, if (custom) args else normalize(args), custom)

    /** 参数按 JSON 语义比较（空串与 `{}` 等价）。 */
    private fun normalize(args: String): String =
        if (args.isBlank()) "{}" else runCatching { json.parseToJsonElement(args).toString() }.getOrDefault(args)

    private fun diff(a: Summary, b: Summary): String = buildString {
        if (a.text != b.text) append("text ${a.text.length} chars → ${b.text.length} chars (\"${a.text.take(40)}\" vs \"${b.text.take(40)}\") ")
        if (a.reasoning != b.reasoning) append("reasoning ${a.reasoning.length} → ${b.reasoning.length} chars ")
        if (a.finish != b.finish) append("finish ${a.finish} → ${b.finish} ")
        if (a.usage != b.usage) append("usage ${a.usage} → ${b.usage} ")
        if (a.tools != b.tools) append("tools ${a.tools.map { it.name }} → ${b.tools.map { it.name }} ${if (a.tools.map { it.name } == b.tools.map { it.name }) "arguments ${a.tools.map { it.arguments.take(80) }} vs ${b.tools.map { it.arguments.take(80) }}" else ""}")
    }

    private fun report(count: Int, failures: List<String>) {
        assertTrue(count > 0, "no fixtures found under ${root()}")
        if (failures.isNotEmpty()) fail("${failures.size} of $count fixtures failed:\n" + failures.joinToString("\n"))
    }

    // ───────────────────────── 文件 ─────────────────────────

    private fun root(): String = (getenv("NETON_AI_FIXTURES")?.toKString() ?: "src/commonTest/fixtures") + "/vercel-ai"

    private fun forEachFixture(suffix: String, block: (String, WireFormat, String, String) -> Unit) {
        for ((dir, format) in sets) for (name in list("${root()}/$dir").filter { it.endsWith(suffix) }.sorted()) {
            block(dir, format, name, read("${root()}/$dir/$name"))
        }
    }

    private fun list(path: String): List<String> {
        val d = opendir(path) ?: fail("cannot open $path")
        val out = mutableListOf<String>()
        try {
            while (true) {
                val e = readdir(d) ?: break
                val n = e.pointed.d_name.toKString()
                if (!n.startsWith(".")) out += n
            }
        } finally {
            closedir(d)
        }
        return out
    }

    private fun read(path: String): String {
        val f = fopen(path, "rb") ?: fail("cannot open $path")
        try {
            val out = StringBuilder()
            val bytes = mutableListOf<ByteArray>()
            memScoped {
                val buf = allocArray<ByteVar>(65536)
                while (true) {
                    val n = fread(buf, 1u, 65536u, f).toInt()
                    if (n <= 0) break
                    bytes += buf.readBytes(n)
                }
            }
            val all = ByteArray(bytes.sumOf { it.size })
            var i = 0
            for (b in bytes) { b.copyInto(all, i); i += b.size }
            out.append(all.decodeToString())
            return out.toString()
        } finally {
            fclose(f)
        }
    }
}
