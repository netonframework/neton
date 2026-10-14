package neton.ai.protocol.profile

import kotlinx.serialization.json.JsonObject
import neton.ai.protocol.ir.WireFormat

/**
 * 厂商配置文件：用**数据**描述同一协议族内的厂商差异（spec/ai.md §9）。
 *
 * 取值来自各家官方文档（2026-10-15 核对，出处见 [ProviderProfiles] 各项注释）。文档没写明的能力按
 * 「不支持」处理：未知厂商使用 [ProviderProfiles.CONSERVATIVE]，不发送没有声明支持的字段，
 * 宁可少一项能力，也不让上游因为不认识的字段返回 400。
 *
 * 只作用于 OpenAI Chat Completions 族（其他协议的报文由各自的编解码器按官方规范生成）。
 */
data class ProviderProfile(
    val name: String,
    /** 官方主机名；`*.` 前缀表示任意子域。 */
    val hosts: List<String> = emptyList(),
    /** 流式请求是否发送 `stream_options.include_usage`。默认在尾包给 usage 的厂商不需要，也不发。 */
    val streamUsage: Boolean = false,
    /** 输出上限字段：`max_tokens` 或 `max_completion_tokens`（OpenAI 推理模型拒绝前者）。 */
    val maxTokensField: String = "max_tokens",
    /** 是否接受 `parallel_tool_calls`；不接受时去掉该字段。 */
    val parallelToolCalls: Boolean = false,
) {
    /** 按配置调整编码好的请求报文。 */
    fun adapt(format: WireFormat, body: JsonObject): JsonObject {
        if (format != WireFormat.OPENAI_CHAT) return body
        val m = LinkedHashMap(body)
        if (!streamUsage) m.remove("stream_options")
        if (!parallelToolCalls) m.remove("parallel_tool_calls")
        val other = if (maxTokensField == "max_tokens") "max_completion_tokens" else "max_tokens"
        // 两个字段在部分厂商互斥（如火山方舟），只保留配置要求的那一个
        m.remove(other)?.let { if (maxTokensField !in m) m[maxTokensField] = it }
        return JsonObject(m)
    }

    fun matchesHost(host: String): Boolean = hosts.any { h ->
        if (h.startsWith("*.")) host.endsWith(h.substring(1)) else host == h
    }
}

object ProviderProfiles {
    /** developers.openai.com/api/reference/resources/chat：流式只有 include_usage 时尾包才有 usage；max_tokens 已废弃，推理模型拒收。 */
    val OPENAI = ProviderProfile("openai", listOf("api.openai.com", "*.api.openai.com"),
        streamUsage = true, maxTokensField = "max_completion_tokens", parallelToolCalls = true)

    /** learn.microsoft.com/rest/api/microsoft-foundry/azureopenai/chat：与 OpenAI 相同，max_tokens 不兼容 o 系列。 */
    val AZURE = ProviderProfile("azure", listOf("*.openai.azure.com", "*.services.ai.azure.com"),
        streamUsage = true, maxTokensField = "max_completion_tokens", parallelToolCalls = true)

    /** api-docs.deepseek.com/api/create-chat-completion：支持 include_usage；只有 max_tokens；parallel_tool_calls 未写明。 */
    val DEEPSEEK = ProviderProfile("deepseek", listOf("api.deepseek.com"), streamUsage = true)

    /** help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions：不开 include_usage 流式无 usage；支持 parallel_tool_calls。 */
    val QWEN = ProviderProfile("qwen", listOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "*.maas.aliyuncs.com"),
        streamUsage = true, parallelToolCalls = true)

    /** platform.kimi.ai/docs/api/chat：支持 include_usage；max_tokens 已废弃。 */
    val KIMI = ProviderProfile("kimi", listOf("api.moonshot.ai", "api.moonshot.cn"),
        streamUsage = true, maxTokensField = "max_completion_tokens")

    /** docs.bigmodel.cn/cn/guide/capabilities/streaming：usage 默认在最后一个 chunk；stream_options 未写明。 */
    val GLM = ProviderProfile("glm", listOf("open.bigmodel.cn", "api.z.ai"))

    /** docs.volcengine.com/docs/82379/1494384：支持 include_usage；max_tokens 与 max_completion_tokens 互斥；支持 parallel_tool_calls。 */
    val DOUBAO = ProviderProfile("doubao", listOf("ark.cn-beijing.volces.com", "*.volces.com", "ark.ap-southeast.bytepluses.com"),
        streamUsage = true, parallelToolCalls = true)

    /** platform.minimax.io/docs/api-reference/text-openai-api：支持 include_usage；推荐 max_completion_tokens。 */
    val MINIMAX = ProviderProfile("minimax", listOf("api.minimax.io", "api.minimax.cn"),
        streamUsage = true, maxTokensField = "max_completion_tokens")

    /** docs.siliconflow.cn：stream_options 未写明；只有 max_tokens。 */
    val SILICONFLOW = ProviderProfile("siliconflow", listOf("api.siliconflow.cn", "api.siliconflow.com"))

    /** openrouter.ai/docs/use-cases/usage-accounting：usage 总在最后一个 chunk，include_usage 已无效果。 */
    val OPENROUTER = ProviderProfile("openrouter", listOf("openrouter.ai"), parallelToolCalls = true)

    /** console.groq.com/docs/api-reference：include_usage 未写明；max_tokens 已废弃。 */
    val GROQ = ProviderProfile("groq", listOf("api.groq.com"), maxTokensField = "max_completion_tokens", parallelToolCalls = true)

    /** docs.together.ai/reference/chat-completions-1：stream_options 不在规范里；只有 max_tokens。 */
    val TOGETHER = ProviderProfile("together", listOf("api.together.ai", "api.together.xyz"))

    /** docs.fireworks.ai/api-reference/post-chatcompletions：流式默认带 usage。 */
    val FIREWORKS = ProviderProfile("fireworks", listOf("api.fireworks.ai"), parallelToolCalls = true)

    /** docs.x.ai/developers/rest-api-reference/inference/chat-completions：支持 include_usage；max_tokens 已废弃。 */
    val XAI = ProviderProfile("xai", listOf("api.x.ai", "us.api.x.ai"),
        streamUsage = true, maxTokensField = "max_completion_tokens", parallelToolCalls = true)

    /** docs.mistral.ai/openapi.yaml：请求 additionalProperties=false，没有 stream_options 与 max_completion_tokens。 */
    val MISTRAL = ProviderProfile("mistral", listOf("api.mistral.ai"), parallelToolCalls = true)

    /** docs.vllm.ai：自建服务，按名称选用；include_usage 未写明。 */
    val VLLM = ProviderProfile("vllm", parallelToolCalls = true)

    /** docs.ollama.com/api/openai-compatibility：支持 include_usage；只有 max_tokens；未列 parallel_tool_calls。 */
    val OLLAMA = ProviderProfile("ollama", listOf("ollama.com"), streamUsage = true)

    /** 未知厂商的保守默认值。 */
    val CONSERVATIVE = ProviderProfile("conservative")

    val BUILT_IN: List<ProviderProfile> = listOf(
        OPENAI, AZURE, DEEPSEEK, QWEN, KIMI, GLM, DOUBAO, MINIMAX, SILICONFLOW, OPENROUTER, GROQ, TOGETHER, FIREWORKS, XAI, MISTRAL, VLLM, OLLAMA,
    )

    fun byName(name: String): ProviderProfile? = BUILT_IN.firstOrNull { it.name == name }

    /** 按 baseUrl 的主机名选内置配置；没有收录的返回 [CONSERVATIVE]。 */
    fun forBaseUrl(baseUrl: String): ProviderProfile {
        val host = baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBefore(':').lowercase()
        return BUILT_IN.firstOrNull { it.matchesHost(host) } ?: CONSERVATIVE
    }
}
