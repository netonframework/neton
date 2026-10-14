package neton.ai.builder

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor

class AiToolDefinitionBuilder internal constructor(private val name: String) {
    var description: String = ""
    var inputSchemaJson: String = "{}"
    /** 可以与相邻的其他 parallelSafe 调用并行执行。 */
    var parallelSafe: Boolean = false
    internal var executor: AiToolExecutor? = null
    internal var handler: neton.ai.AiToolHandler? = null

    /** 需要上下文（主体、请求信息、历史）的实现。 */
    fun handle(block: suspend (neton.ai.AiToolCallContext) -> String) {
        handler = neton.ai.AiToolHandler { block(it) }
    }

    fun execute(handler: suspend (argumentsJson: String) -> String) {
        executor = AiToolExecutor { handler(it) }
    }

    internal fun build(): AiToolDefinition = AiToolDefinition(
        name = name,
        description = description,
        inputSchemaJson = inputSchemaJson,
        parallelSafe = parallelSafe,
        handler = handler,
        executor = executor,
    )

    @PublishedApi
    internal companion object {
        val internalJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * 类型化工具：入参与返回值按 kotlinx.serialization 编解码。没有显式给出 inputSchemaJson 时，
 * 从 TIn 的序列化描述生成 schema（[neton.ai.JsonSchemas]，只支持其中列出的子集）。
 */
inline fun <reified TIn, reified TOut> AiToolDefinitionBuilder.execute(
    crossinline handler: suspend (TIn) -> TOut,
) {
    val inSer = serializer<TIn>()
    val outSer = serializer<TOut>()
    if (inputSchemaJson == "{}") inputSchemaJson = neton.ai.JsonSchemas.of(inSer).toString()
    execute { argsJson ->
        val input = AiToolDefinitionBuilder.internalJson.decodeFromString(inSer, argsJson)
        val output = handler(input)
        AiToolDefinitionBuilder.internalJson.encodeToString(outSer, output)
    }
}
