package neton.ai.builder

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor

class AiToolDefinitionBuilder internal constructor(private val name: String) {
    var description: String = ""
    var inputSchemaJson: String = "{}"
    internal var executor: AiToolExecutor? = null

    fun execute(handler: suspend (argumentsJson: String) -> String) {
        executor = AiToolExecutor { handler(it) }
    }

    internal fun build(): AiToolDefinition = AiToolDefinition(
        name = name,
        description = description,
        inputSchemaJson = inputSchemaJson,
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

/** Typed-codec helper: caller still passes inputSchemaJson; codec only handles decode/encode. */
inline fun <reified TIn, reified TOut> AiToolDefinitionBuilder.execute(
    crossinline handler: suspend (TIn) -> TOut,
) {
    val inSer = serializer<TIn>()
    val outSer = serializer<TOut>()
    execute { argsJson ->
        val input = AiToolDefinitionBuilder.internalJson.decodeFromString(inSer, argsJson)
        val output = handler(input)
        AiToolDefinitionBuilder.internalJson.encodeToString(outSer, output)
    }
}
