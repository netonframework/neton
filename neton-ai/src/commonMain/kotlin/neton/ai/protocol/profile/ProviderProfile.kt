package neton.ai.protocol.profile

/**
 * 厂商配置文件：用**数据**描述同一协议族内的厂商差异（spec/ai.md §9）。
 *
 * 只收录已按厂商文档核对过的差异；未知厂商使用保守默认值——不发送没有声明支持的字段，
 * 宁可少一项能力，也不让上游因为不认识的字段返回 400。
 */
data class ProviderProfile(
    val name: String,
    /** 流式请求是否发送 `stream_options.include_usage`（OpenAI Chat Completions 族）。 */
    val streamUsage: Boolean = false,
)

object ProviderProfiles {
    /** OpenAI 官方：流式只有在 `stream_options.include_usage=true` 时才在尾包返回 usage。 */
    val OPENAI = ProviderProfile(name = "openai", streamUsage = true)

    /** 未知厂商的保守默认值。 */
    val CONSERVATIVE = ProviderProfile(name = "conservative")

    private val byHost: Map<String, ProviderProfile> = mapOf(
        "api.openai.com" to OPENAI,
    )

    /** 按 baseUrl 的主机名选内置配置；没有收录的返回 [CONSERVATIVE]。 */
    fun forBaseUrl(baseUrl: String): ProviderProfile {
        val host = baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBefore(':').lowercase()
        return byHost[host] ?: CONSERVATIVE
    }
}
