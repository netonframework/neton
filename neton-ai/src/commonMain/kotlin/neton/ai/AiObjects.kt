package neton.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import neton.ai.protocol.ir.ResponseFormat

/**
 * 结构化输出的结果（spec §10.2）。失败分成可区分的几类，调用方据此决定重试、降级或报错：
 * 拒绝不是非法输出，截断也不是非法输出。
 */
sealed interface AiObjectResult<out T> {
    data class Success<T>(val value: T, val json: JsonElement, val text: String) : AiObjectResult<T>

    /** 模型拒绝回答（refusal 内容或内容过滤）。 */
    data class Refused(val message: String) : AiObjectResult<Nothing>

    /** 输出达到长度上限被截断，JSON 不完整。 */
    data class Truncated(val partialText: String) : AiObjectResult<Nothing>

    /** 输出不是合法 JSON，或不符合目标类型。 */
    data class Invalid(val reason: String, val text: String) : AiObjectResult<Nothing>

    /** 调用本身失败（网络、鉴权等）。 */
    data class Failed(val error: AiError) : AiObjectResult<Nothing>
}

sealed interface AiObjectStreamEvent<out T> {
    /**
     * 中途的半成品：[partial] 是把已收到的文本补全括号后尽力解析出的 JSON，可能为 null。
     * **它不是结果**，只用于展示进度；最终结果只看 [Final]。
     */
    data class Partial(val text: String, val partial: JsonElement?) : AiObjectStreamEvent<Nothing>

    data class Final<T>(val result: AiObjectResult<T>) : AiObjectStreamEvent<T>
}

private val objectJson = Json { ignoreUnknownKeys = false; isLenient = false }

/**
 * 生成符合 [serializer] 的对象：按 JSON Schema 约束输出（[strict] 对应 OpenAI 的 strict 模式），
 * 再按类型解码校验。工具循环照常可用。
 */
suspend fun <T> AiClient.generateObject(
    serializer: KSerializer<T>,
    request: GenerateTextRequest,
    schemaName: String = "response",
    strict: Boolean = true,
): AiObjectResult<T> {
    val format = ResponseFormat.JsonSchema(schemaName, JsonSchemas.of(serializer, strict), strict)
    val result = try {
        generateText(request.copy(responseFormat = format))
    } catch (e: AiException) {
        return AiObjectResult.Failed(e.error)
    }
    return interpret(serializer, result.message, result.finishReason)
}

suspend inline fun <reified T> AiClient.generateObject(request: GenerateTextRequest, strict: Boolean = true): AiObjectResult<T> =
    generateObject(serializer<T>(), request, strict = strict)

/** 流式结构化输出：先发若干 [AiObjectStreamEvent.Partial]，最后恰好一个 [AiObjectStreamEvent.Final]。 */
fun <T> AiClient.streamObject(
    serializer: KSerializer<T>,
    request: StreamTextRequest,
    schemaName: String = "response",
    strict: Boolean = true,
): Flow<AiObjectStreamEvent<T>> = flow {
    val format = ResponseFormat.JsonSchema(schemaName, JsonSchemas.of(serializer, strict), strict)
    val text = StringBuilder()
    try {
        streamText(request.copy(responseFormat = format)).collect { e ->
            when (e) {
                is AiStreamEvent.TextDelta -> {
                    text.append(e.text)
                    emit(AiObjectStreamEvent.Partial(text.toString(), PartialJson.parse(text.toString())))
                }
                is AiStreamEvent.Completed -> emit(AiObjectStreamEvent.Final(interpret(serializer, e.message, e.finishReason)))
                is AiStreamEvent.Failed -> emit(AiObjectStreamEvent.Final(AiObjectResult.Failed(e.error)))
                else -> {}
            }
        }
    } catch (e: AiException) {
        emit(AiObjectStreamEvent.Final(AiObjectResult.Failed(e.error)))
    }
}

internal fun <T> interpret(serializer: KSerializer<T>, message: AiMessage, finish: AiFinishReason): AiObjectResult<T> {
    message.content.filterIsInstance<AiContent.Refusal>().firstOrNull()?.let { return AiObjectResult.Refused(it.text) }
    val text = message.text
    if (finish == AiFinishReason.ContentFilter) return AiObjectResult.Refused(text.ifEmpty { "content filtered" })
    if (finish == AiFinishReason.Length) return AiObjectResult.Truncated(text)
    val body = stripFence(text)
    val json = try {
        objectJson.parseToJsonElement(body)
    } catch (e: Exception) {
        return AiObjectResult.Invalid("not valid JSON: ${e.message?.take(200)}", text)
    }
    return try {
        AiObjectResult.Success(objectJson.decodeFromJsonElement(serializer, json), json, text)
    } catch (e: Exception) {
        AiObjectResult.Invalid("does not match the expected type: ${e.message?.take(200)}", text)
    }
}

/** 部分兼容服务即使在 JSON 模式下也会包一层 ``` 代码块；只去掉这一层，不做别的修补。 */
private fun stripFence(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

/** 半成品 JSON 的尽力解析：补全未闭合的字符串与括号。只用于展示进度。 */
internal object PartialJson {
    fun parse(text: String): JsonElement? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in t) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val sb = StringBuilder(t)
        if (inString) { if (escaped) sb.setLength(sb.length - 1); sb.append('"') }
        // 去掉悬空的逗号 / 冒号，避免补全后仍不合法
        while (sb.isNotEmpty() && (sb.last() == ',' || sb.last() == ':' || sb.last().isWhitespace())) sb.setLength(sb.length - 1)
        if (sb.endsWith(":")) sb.append("null")
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return runCatching { Json.parseToJsonElement(sb.toString()) }.getOrNull()
    }
}
