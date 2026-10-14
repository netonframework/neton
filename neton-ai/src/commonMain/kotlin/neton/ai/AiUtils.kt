package neton.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sqrt

/** 消息裁剪的范围。 */
enum class AiPrune { None, All, BeforeLastMessage }

/**
 * 裁剪历史以节省上下文（spec/ai.md §10.3）：
 * - [reasoning]：去掉 assistant 消息里的推理块；
 * - [toolCalls]：去掉工具调用、结果与审批；[keepLastMessages] 条之内的、以及与保留部分关联的调用/结果/审批不动；
 * - [removeEmptyMessages]：去掉裁剪后没有内容的消息。
 *
 * 注意：带签名的推理（Anthropic thinking）在仍需回传的工具轮次里不能去掉，裁剪只应作用于已结束的轮次。
 */
fun pruneMessages(
    messages: List<AiMessage>,
    reasoning: AiPrune = AiPrune.None,
    toolCalls: AiPrune = AiPrune.None,
    keepLastMessages: Int = 1,
    removeEmptyMessages: Boolean = true,
): List<AiMessage> {
    val n = messages.size
    fun inTail(i: Int, rule: AiPrune) = rule == AiPrune.BeforeLastMessage && i >= n - keepLastMessages
    // 保留区里出现的调用 / 审批 id：它们在前面的配对部分也要保留
    val keptIds = HashSet<String>()
    if (toolCalls == AiPrune.BeforeLastMessage) {
        messages.withIndex().filter { inTail(it.index, toolCalls) }.forEach { (_, m) ->
            m.content.forEach { c ->
                when (c) {
                    is AiContent.ToolCall -> keptIds += c.call.id
                    is AiContent.ToolResult -> keptIds += c.toolCallId
                    is AiContent.ToolApprovalRequest -> { keptIds += c.approvalId; keptIds += c.toolCallId }
                    is AiContent.ToolApprovalResponse -> keptIds += c.approvalId
                    else -> {}
                }
            }
        }
    }
    val out = messages.mapIndexed { i, m ->
        val content = m.content.filter { c ->
            val pruneReasoning = reasoning != AiPrune.None && !inTail(i, reasoning) && m.role == AiRole.Assistant
            val pruneTools = toolCalls != AiPrune.None && !inTail(i, toolCalls)
            when (c) {
                is AiContent.Reasoning -> !pruneReasoning
                is AiContent.ToolCall -> !pruneTools || c.call.id in keptIds
                is AiContent.ToolResult -> !pruneTools || c.toolCallId in keptIds
                is AiContent.ToolApprovalRequest -> !pruneTools || c.approvalId in keptIds || c.toolCallId in keptIds
                is AiContent.ToolApprovalResponse -> !pruneTools || c.approvalId in keptIds
                else -> true
            }
        }
        m.copy(content = content)
    }
    return if (removeEmptyMessages) out.filter { it.content.isNotEmpty() } else out
}

/** 平滑流的切分方式。 */
enum class AiChunking { Word, Line }

/**
 * 平滑输出：把文本与推理增量按词（或行）重新切分并按 [delayMs] 匀速发出，其他事件先冲刷缓冲再原样通过。
 * 只改变展示节奏，不改变内容。
 */
fun Flow<AiStreamPart>.smooth(delayMs: Long = 10, chunking: AiChunking = AiChunking.Word): Flow<AiStreamPart> = flow {
    var buffer = StringBuilder()
    var bufferedId: String? = null
    var reasoning = false
    val pattern = if (chunking == AiChunking.Word) Regex("\\S+\\s+") else Regex("[^\\n]*\\n")

    suspend fun emitChunk(text: String) {
        val id = bufferedId ?: return
        emit(if (reasoning) AiStreamPart.ReasoningDelta(id, text) else AiStreamPart.TextDelta(id, text))
    }
    suspend fun flushAll() {
        if (buffer.isNotEmpty()) { emitChunk(buffer.toString()); buffer = StringBuilder() }
    }

    collect { p ->
        val (id, text, isReasoning) = when (p) {
            is AiStreamPart.TextDelta -> Triple(p.id, p.text, false)
            is AiStreamPart.ReasoningDelta -> Triple(p.id, p.text, true)
            else -> { flushAll(); emit(p); return@collect }
        }
        if (bufferedId != id || reasoning != isReasoning) { flushAll(); bufferedId = id; reasoning = isReasoning }
        buffer.append(text)
        while (true) {
            val m = pattern.find(buffer) ?: break
            if (m.range.first != 0) break
            emitChunk(m.value)
            buffer = StringBuilder(buffer.substring(m.range.last + 1))
            if (delayMs > 0) delay(delayMs)
        }
    }
    flushAll()
}

/** 余弦相似度；长度不同抛 [IllegalArgumentException]，零向量返回 0。 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "vectors must have the same length (${a.size} vs ${b.size})" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0.0
    return dot / (sqrt(na) * sqrt(nb))
}
