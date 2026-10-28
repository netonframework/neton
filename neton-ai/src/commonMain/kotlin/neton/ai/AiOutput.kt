package neton.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import neton.ai.internal.Jsons
import neton.ai.internal.PartialJson
import neton.ai.protocol.ir.ResponseFormat

/**
 * 结构化输出声明（spec/ai.md §10.2）。放进 [AiRequest.output]，只解析**最后一步**的文本；
 * 工具循环照常进行。流式时持续给出半成品（[AiStreamPart.PartialOutput]），**半成品不是结果**。
 */
@neton.ai.ExperimentalNetonAiApi
sealed class AiOutput<T> {
    abstract val responseFormat: ResponseFormat?

    /** 解析完整输出；不合法时抛 [AiOutputException]。 */
    abstract fun parse(text: String): T

    /** 尽力解析半成品（补全括号后解析），用于展示进度。 */
    open fun parsePartial(text: String): JsonElement? = PartialJson.parse(text)

    /** 纯文本（默认，不发送格式约束）。 */
    data object Text : AiOutput<String>() {
        override val responseFormat: ResponseFormat? = null
        override fun parse(text: String) = text
        override fun parsePartial(text: String): JsonElement = JsonPrimitive(text)
    }

    /** 任意 JSON（只要求是合法 JSON，不约束结构）。 */
    data object Json : AiOutput<JsonElement>() {
        override val responseFormat: ResponseFormat = ResponseFormat.JsonObject
        override fun parse(text: String): JsonElement = parseJson(text)
    }

    /** 符合 [serializer] 的对象（schema 从序列化描述生成，[strict] 对应 OpenAI strict 模式）。 */
    class Object<T>(
        val serializer: KSerializer<T>,
        val name: String = "response",
        val description: String? = null,
        val strict: Boolean = true,
    ) : AiOutput<T>() {
        override val responseFormat = ResponseFormat.JsonSchema(name, JsonSchemas.of(serializer, strict), strict, description)
        override fun parse(text: String): T = decode(serializer, parseJson(text))
    }

    /**
     * 元素列表。多数厂商的结构化输出要求根是对象，所以按 `{"elements": [...]}` 包一层，解析后去掉。
     * 半成品只包含已经完整的元素。
     */
    class ArrayOf<T>(
        val element: KSerializer<T>,
        val name: String = "response",
        val strict: Boolean = true,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : AiOutput<List<T>>() {
        override val responseFormat = ResponseFormat.JsonSchema(name, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("elements", buildJsonObject {
                    put("type", "array")
                    put("items", JsonSchemas.of(element, strict))
                    minItems?.let { put("minItems", it) }
                    maxItems?.let { put("maxItems", it) }
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("elements")) })
            put("additionalProperties", false)
        }, strict)

        override fun parse(text: String): List<T> {
            val elements = (parseJson(text) as? JsonObject)?.get("elements") as? JsonArray
                ?: throw AiOutputException(AiOutputResult.Invalid("expected an object with an 'elements' array", text))
            if (minItems != null && elements.size < minItems) throw AiOutputException(AiOutputResult.Invalid("expected at least $minItems elements", text))
            if (maxItems != null && elements.size > maxItems) throw AiOutputException(AiOutputResult.Invalid("expected at most $maxItems elements", text))
            return decode(ListSerializer(element), elements)
        }

        /** 半成品：最后一个元素可能不完整，只保留能按元素类型解码的部分。 */
        override fun parsePartial(text: String): JsonElement? {
            val repaired = PartialJson.parse(text) as? JsonObject ?: return null
            val elements = repaired["elements"] as? JsonArray ?: return JsonArray(emptyList())
            val complete = if (PartialJson.isComplete(text)) elements else JsonArray(elements.dropLast(1))
            return JsonArray(complete.filter { runCatching { Jsons.strict.decodeFromJsonElement(element, it) }.isSuccess })
        }
    }

    /** 从给定选项里选一个（按 `{"result": "<选项>"}` 约束）。 */
    class Choice(val options: List<String>, val name: String = "response") : AiOutput<String>() {
        override val responseFormat = ResponseFormat.JsonSchema(name, buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("result", buildJsonObject { put("type", "string"); put("enum", JsonArray(options.map(::JsonPrimitive))) })
            })
            put("required", buildJsonArray { add(JsonPrimitive("result")) })
            put("additionalProperties", false)
        }, true)

        override fun parse(text: String): String {
            val v = ((parseJson(text) as? JsonObject)?.get("result") as? JsonPrimitive)?.contentOrNull
            if (v == null || v !in options) throw AiOutputException(AiOutputResult.Invalid("expected one of $options", text))
            return v
        }

        /** 半成品：前缀只匹配一个选项时才给出。 */
        override fun parsePartial(text: String): JsonElement? {
            val v = ((PartialJson.parse(text) as? JsonObject)?.get("result") as? JsonPrimitive)?.contentOrNull ?: return null
            val matches = options.filter { it.startsWith(v) }
            return matches.singleOrNull()?.let { JsonPrimitive(it) }
        }
    }

    companion object {
        inline fun <reified T> obj(name: String = "response", description: String? = null, strict: Boolean = true) =
            Object(serializer<T>(), name, description, strict)

        inline fun <reified T> array(name: String = "response", strict: Boolean = true, minItems: Int? = null, maxItems: Int? = null) =
            ArrayOf(serializer<T>(), name, strict, minItems, maxItems)

        fun choice(vararg options: String) = Choice(options.toList())

        /** 部分兼容服务即使在 JSON 模式下也会包一层 ``` 代码块；只去掉这一层，不做别的修补。 */
        internal fun parseJson(text: String): JsonElement {
            val t = text.trim().let { if (it.startsWith("```")) it.removePrefix("```json").removePrefix("```").removeSuffix("```").trim() else it }
            return try {
                Jsons.strict.parseToJsonElement(t)
            } catch (e: Exception) {
                throw AiOutputException(AiOutputResult.Invalid("not valid JSON: ${e.message?.take(200)}", text))
            }
        }

        internal fun <T> decode(serializer: KSerializer<T>, json: JsonElement): T = try {
            Jsons.strict.decodeFromJsonElement(serializer, json)
        } catch (e: Exception) {
            throw AiOutputException(AiOutputResult.Invalid("does not match the expected type: ${e.message?.take(200)}", json.toString()))
        }
    }
}

/** 结构化输出的结果：拒绝不是非法输出，截断也不是非法输出，调用方据此决定重试、降级或报错。 */
@neton.ai.ExperimentalNetonAiApi
sealed interface AiOutputResult<out T> {
    data class Success<T>(val value: T) : AiOutputResult<T>
    /** 模型拒绝回答（refusal 内容或内容过滤）。 */
    data class Refused(val message: String) : AiOutputResult<Nothing>
    /** 输出达到长度上限被截断。 */
    data class Truncated(val partialText: String) : AiOutputResult<Nothing>
    /** 不是合法 JSON，或不符合声明的类型。 */
    data class Invalid(val reason: String, val text: String) : AiOutputResult<Nothing>
    /** 最后一步没有产生可解析的输出（例如以工具调用结束、等待审批）。 */
    data class NotGenerated(val reason: String) : AiOutputResult<Nothing>
}

@neton.ai.ExperimentalNetonAiApi
class AiOutputException(val result: AiOutputResult<Nothing>) : RuntimeException(
    when (result) {
        is AiOutputResult.Success -> "output generated"
        is AiOutputResult.Refused -> "model refused: ${result.message}"
        is AiOutputResult.Truncated -> "output truncated at the length limit"
        is AiOutputResult.Invalid -> "invalid output: ${result.reason}"
        is AiOutputResult.NotGenerated -> "no output generated: ${result.reason}"
    },
)
