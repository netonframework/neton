package neton.ai.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.ir.*

@neton.ai.ExperimentalNetonAiApi
enum class AiCallMode { GENERATE, STREAM }

/**
 * 语言模型中间件。三个钩子都作用在协议层 IR 上，所以对所有协议、所有 provider 生效：
 * - [transformRequest]：改写请求（两种调用模式都会经过）；
 * - [wrapGenerate] / [wrapStream]：包住实际调用，可改写结果，也可以用另一种模式实现（如模拟流式）。
 */
@neton.ai.ExperimentalNetonAiApi
interface AiModelMiddleware {
    suspend fun transformRequest(request: ChatRequest, mode: AiCallMode, model: AiLanguageModel): ChatRequest = request

    suspend fun wrapGenerate(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelResult,
    ): AiModelResult = next(request)

    suspend fun wrapStream(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelStream,
        generate: suspend (ChatRequest) -> AiModelResult,
    ): AiModelStream = next(request)
}

/**
 * 给模型套上中间件。列表中的**第一个**最先改写请求、最外层包住调用；最后一个紧贴模型。
 */
@neton.ai.ExperimentalNetonAiApi
fun AiLanguageModel.withMiddleware(middleware: List<AiModelMiddleware>): AiLanguageModel =
    middleware.asReversed().fold(this) { inner, m -> MiddlewareModel(inner, m) }

@neton.ai.ExperimentalNetonAiApi
fun AiLanguageModel.withMiddleware(vararg middleware: AiModelMiddleware): AiLanguageModel = withMiddleware(middleware.toList())

private class MiddlewareModel(private val inner: AiLanguageModel, private val m: AiModelMiddleware) : AiLanguageModel {
    override val provider: String get() = inner.provider
    override val modelId: String get() = inner.modelId

    override suspend fun generate(request: ChatRequest, options: AiModelCallOptions): AiModelResult {
        val r = m.transformRequest(request, AiCallMode.GENERATE, inner)
        return m.wrapGenerate(r, options, inner) { inner.generate(it, options) }
    }

    override suspend fun stream(request: ChatRequest, options: AiModelCallOptions): AiModelStream {
        val r = m.transformRequest(request, AiCallMode.STREAM, inner)
        return m.wrapStream(r, options, inner, { inner.stream(it, options) }, { inner.generate(it, options) })
    }
}

/** 内置中间件。 */
@neton.ai.ExperimentalNetonAiApi
object AiMiddlewares {

    /** 请求里没有设置的选项用这里的默认值补上（请求显式给出的值优先；厂商选项按命名空间合并）。 */
    fun defaultSettings(defaults: CallOptions) = object : AiModelMiddleware {
        override suspend fun transformRequest(request: ChatRequest, mode: AiCallMode, model: AiLanguageModel): ChatRequest {
            val o = request.options
            val mergedProvider = (defaults.providerOptions.keys + o.providerOptions.keys).associateWith { k ->
                JsonObject((defaults.providerOptions[k] ?: JsonObject(emptyMap())) + (o.providerOptions[k] ?: JsonObject(emptyMap())))
            }
            return request.copy(options = o.copy(
                maxOutputTokens = o.maxOutputTokens ?: defaults.maxOutputTokens,
                temperature = o.temperature ?: defaults.temperature,
                topP = o.topP ?: defaults.topP,
                topK = o.topK ?: defaults.topK,
                stop = o.stop.ifEmpty { defaults.stop },
                seed = o.seed ?: defaults.seed,
                presencePenalty = o.presencePenalty ?: defaults.presencePenalty,
                frequencyPenalty = o.frequencyPenalty ?: defaults.frequencyPenalty,
                toolChoice = o.toolChoice ?: defaults.toolChoice,
                parallelToolCalls = o.parallelToolCalls ?: defaults.parallelToolCalls,
                responseFormat = o.responseFormat ?: defaults.responseFormat,
                reasoning = o.reasoning ?: defaults.reasoning,
                user = o.user ?: defaults.user,
                providerOptions = mergedProvider,
            ))
        }
    }

    /** 请求没有任何系统指令时补上 [instructions]。 */
    fun defaultInstructions(instructions: String) = object : AiModelMiddleware {
        override suspend fun transformRequest(request: ChatRequest, mode: AiCallMode, model: AiLanguageModel): ChatRequest =
            if (request.instructions.isNotEmpty() || request.messages.any { it.role == Role.System || it.role == Role.Developer }) request
            else request.copy(instructions = listOf(Instruction(instructions)))
    }

    /**
     * 把正文里 `<tag>…</tag>` 包着的推理抽成推理块（DeepSeek-R1 蒸馏模型、MiniMax 默认输出、部分自建服务）。
     * @param startWithReasoning 输出开头没有开标签、直接就是推理（部分模型省略开标签）。
     */
    fun extractReasoning(tag: String = "think", separator: String = "\n", startWithReasoning: Boolean = false): AiModelMiddleware =
        ExtractReasoning("<$tag>", "</$tag>", separator, startWithReasoning)

    /** 去掉正文外层的 ```json 代码块（部分模型在 JSON 模式下仍会包一层）。 */
    fun extractJson(): AiModelMiddleware = ExtractJson

    /** 用一次非流式调用模拟流式（上游不支持流式、或流式不稳定时）。 */
    fun simulateStreaming(): AiModelMiddleware = SimulateStreaming
}

// ───────────────────────── extractReasoning ─────────────────────────

private class ExtractReasoning(
    private val open: String,
    private val close: String,
    private val separator: String,
    private val startWithReasoning: Boolean,
) : AiModelMiddleware {

    override suspend fun wrapGenerate(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelResult,
    ): AiModelResult {
        val r = next(request)
        val out = mutableListOf<Block>()
        for (b in r.response.content) {
            if (b !is TextBlock) { out += b; continue }
            val (reasoning, text) = split((if (startWithReasoning) open else "") + b.text)
            if (reasoning.isNotEmpty()) out += ReasoningBlock(reasoning.joinToString(separator))
            if (text.isNotEmpty()) out += b.copy(text = text)
        }
        return r.copy(response = r.response.copy(content = out))
    }

    /** 返回（推理段落列表, 去掉推理后的正文）。 */
    private fun split(text: String): Pair<List<String>, String> {
        val reasoning = mutableListOf<String>()
        val rest = StringBuilder()
        var i = 0
        while (i < text.length) {
            val s = text.indexOf(open, i)
            if (s < 0) { rest.append(text, i, text.length); break }
            rest.append(text, i, s)
            val e = text.indexOf(close, s + open.length)
            if (e < 0) { reasoning += text.substring(s + open.length); i = text.length; break }
            reasoning += text.substring(s + open.length, e)
            i = e + close.length
        }
        // 推理后面紧跟的换行属于格式，不是正文
        return reasoning to rest.toString().trimStart('\n')
    }

    override suspend fun wrapStream(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelStream,
        generate: suspend (ChatRequest) -> AiModelResult,
    ): AiModelStream {
        val s = next(request)
        return AiModelStream(s.warnings, transform(s.events))
    }

    /**
     * 流式状态机：文本增量逐字检测标签，在推理块与文本块之间切换。
     * 其他块原样通过（重新编号，保证块序号连续）。可能是标签前缀的尾部先留住，等下一段再判断。
     */
    private fun transform(events: Flow<StreamEvent>): Flow<StreamEvent> = flow {
        var next = 0
        val remap = HashMap<Int, Int>()
        val textBlocks = HashSet<Int>()
        var inReasoning = startWithReasoning
        var pending = ""
        var current: Int? = null      // 当前打开的合成块序号
        var currentIsReasoning = false
        var afterReasoning = false    // 刚结束推理：吞掉紧随的换行

        suspend fun closeCurrent() {
            current?.let { emit(StreamEvent.BlockStop(it)) }
            current = null
        }
        suspend fun out(text: String, reasoning: Boolean) {
            var t = text
            if (!reasoning && afterReasoning) { t = t.trimStart('\n'); if (t.isEmpty()) return; afterReasoning = false }
            if (t.isEmpty()) return
            if (current == null || currentIsReasoning != reasoning) {
                closeCurrent()
                val idx = next++
                current = idx
                currentIsReasoning = reasoning
                emit(StreamEvent.BlockStart(idx, if (reasoning) ReasoningBlock() else TextBlock("")))
            }
            emit(StreamEvent.BlockDelta(current!!, if (reasoning) Delta.Reasoning(t) else Delta.Text(t)))
        }
        suspend fun feed(text: String) {
            var buf = pending + text
            pending = ""
            while (buf.isNotEmpty()) {
                val tag = if (inReasoning) close else open
                val idx = buf.indexOf(tag)
                if (idx >= 0) {
                    out(buf.substring(0, idx), inReasoning)
                    buf = buf.substring(idx + tag.length)
                    if (inReasoning) afterReasoning = true
                    inReasoning = !inReasoning
                    if (current != null) closeCurrent()
                    continue
                }
                // 尾部可能是标签的前缀：留到下一段
                val keep = (1 until tag.length).filter { buf.endsWith(tag.substring(0, it)) }.maxOrNull() ?: 0
                out(buf.substring(0, buf.length - keep), inReasoning)
                pending = buf.substring(buf.length - keep)
                buf = ""
            }
        }

        events.collect { e ->
            when (e) {
                is StreamEvent.BlockStart -> if (e.block is TextBlock) {
                    textBlocks += e.index
                    (e.block as TextBlock).text.takeIf { it.isNotEmpty() }?.let { feed(it) }
                } else {
                    closeCurrent()
                    val idx = next++
                    remap[e.index] = idx
                    emit(e.copy(index = idx))
                }
                is StreamEvent.BlockDelta -> if (e.index in textBlocks) {
                    (e.delta as? Delta.Text)?.let { feed(it.text) }
                } else remap[e.index]?.let { emit(e.copy(index = it)) }
                is StreamEvent.BlockStop -> if (e.index in textBlocks) {
                    if (pending.isNotEmpty()) { val p = pending; pending = ""; out(p, inReasoning) }
                    closeCurrent()
                } else remap[e.index]?.let { emit(e.copy(index = it)) }
                else -> emit(e)
            }
        }
        if (pending.isNotEmpty()) out(pending, inReasoning)
        closeCurrent()
    }
}

// ───────────────────────── extractJson ─────────────────────────

private object ExtractJson : AiModelMiddleware {
    private fun strip(t: String): String {
        val s = t.trim()
        if (!s.startsWith("```")) return t
        return s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    override suspend fun wrapGenerate(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelResult,
    ): AiModelResult {
        val r = next(request)
        return r.copy(response = r.response.copy(content = r.response.content.map { if (it is TextBlock) it.copy(text = strip(it.text)) else it }))
    }

    /** 流式：开头先攒够判断是否有围栏，结尾留住可能的 ``` 直到块结束。 */
    override suspend fun wrapStream(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelStream,
        generate: suspend (ChatRequest) -> AiModelResult,
    ): AiModelStream {
        val s = next(request)
        val events = flow {
            val head = HashMap<Int, StringBuilder>()   // 还在判断开头的块
            val fenced = HashSet<Int>()
            val tail = HashMap<Int, String>()
            s.events.collect { e ->
                when {
                    e is StreamEvent.BlockStart && e.block is TextBlock -> { head[e.index] = StringBuilder(); emit(e.copy(block = TextBlock(""))) }
                    e is StreamEvent.BlockDelta && e.delta is Delta.Text && (e.index in head || e.index in tail) -> {
                        var text = (e.delta as Delta.Text).text
                        head[e.index]?.let { h ->
                            h.append(text)
                            val t = h.toString().trimStart()
                            if (t.length < 7 && "```json".startsWith(t)) return@collect
                            head.remove(e.index)
                            text = if (t.startsWith("```")) { fenced += e.index; t.removePrefix("```json").removePrefix("```").trimStart('\n') } else h.toString()
                            tail[e.index] = ""
                        }
                        if (e.index in fenced) {
                            val all = (tail[e.index] ?: "") + text
                            // 尾部的空白与最多三个反引号可能属于闭合围栏，先留住
                            val keep = Regex("\\s*`{0,3}$").find(all)?.value?.length ?: 0
                            tail[e.index] = all.takeLast(keep)
                            text = all.dropLast(keep)
                        }
                        if (text.isNotEmpty()) emit(e.copy(delta = Delta.Text(text)))
                    }
                    e is StreamEvent.BlockStop && (e.index in head || e.index in tail) -> {
                        head.remove(e.index)?.let { if (it.isNotEmpty()) emit(StreamEvent.BlockDelta(e.index, Delta.Text(it.toString()))) }
                        val rest = tail.remove(e.index).orEmpty().let { if (e.index in fenced) it.removeSuffix("```").trimEnd() else it }
                        if (rest.isNotEmpty()) emit(StreamEvent.BlockDelta(e.index, Delta.Text(rest)))
                        emit(e)
                    }
                    else -> emit(e)
                }
            }
        }
        return AiModelStream(s.warnings, events)
    }
}

// ───────────────────────── simulateStreaming ─────────────────────────

private object SimulateStreaming : AiModelMiddleware {
    override suspend fun wrapStream(
        request: ChatRequest, options: AiModelCallOptions, model: AiLanguageModel,
        next: suspend (ChatRequest) -> AiModelStream,
        generate: suspend (ChatRequest) -> AiModelResult,
    ): AiModelStream {
        val r = generate(request.copy(stream = false))
        return AiModelStream(r.warnings, responseAsEvents(r.response))
    }
}

/** 把一次完整响应展开成流式事件（每块一个 start / delta / stop）。 */
@neton.ai.ExperimentalNetonAiApi
fun responseAsEvents(response: ChatResponse): Flow<StreamEvent> = flow {
    emit(StreamEvent.MessageStart(response.id, response.model, response.created))
    response.content.forEachIndexed { i, b ->
        when (b) {
            is TextBlock -> {
                if (b.text.isEmpty() && b.citations.isEmpty()) return@forEachIndexed
                emit(StreamEvent.BlockStart(i, b.copy(text = "")))
                if (b.text.isNotEmpty()) emit(StreamEvent.BlockDelta(i, Delta.Text(b.text)))
            }
            is ReasoningBlock -> {
                emit(StreamEvent.BlockStart(i, b.copy(text = "", signature = null)))
                if (b.text.isNotEmpty()) emit(StreamEvent.BlockDelta(i, Delta.Reasoning(b.text)))
                b.signature?.let { emit(StreamEvent.BlockDelta(i, Delta.Signature(it))) }
            }
            is ToolCallBlock -> {
                emit(StreamEvent.BlockStart(i, b.copy(arguments = "")))
                if (b.arguments.isNotEmpty()) emit(StreamEvent.BlockDelta(i, Delta.Arguments(b.arguments)))
            }
            else -> emit(StreamEvent.BlockStart(i, b))
        }
        emit(StreamEvent.BlockStop(i))
    }
    response.usage?.let { emit(StreamEvent.UsageUpdate(it)) }
    emit(StreamEvent.Finish(response.finishReason ?: FinishReason.Stop, response.rawFinishReason))
}
