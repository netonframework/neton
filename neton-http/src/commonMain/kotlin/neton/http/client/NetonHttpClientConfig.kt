package neton.http.client

import io.ktor.serialization.kotlinx.json.json as ktorJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * Configuration knobs for [newNetonHttpClient].
 *
 * 默认值面向"内网服务到服务"短超时 + JSON 出站场景；上游业务可按需覆盖。
 *
 * 字段：
 * - `connectTimeoutMs` / `requestTimeoutMs` / `socketTimeoutMs`: 三档 ktor timeout，
 *   语义按 [io.ktor.client.plugins.HttpTimeout]。
 * - `expectSuccess`: ktor 的"非 2xx 抛 ResponseException"开关。默认 `false`——业务侧
 *   通常自己解析 envelope 拿 code，比抛异常更可控。
 * - `installJson`: 是否安装 ContentNegotiation + kotlinx-json 反序列化。
 *   关闭时调用方需要自己处理 body。
 * - `json`: 自定义 [Json] 配置（仅 [installJson] = true 时生效）。
 */
data class NetonHttpClientConfig(
    val connectTimeoutMs: Long = 3_000,
    val requestTimeoutMs: Long = 3_000,
    val socketTimeoutMs: Long = 3_000,
    val expectSuccess: Boolean = false,
    val installJson: Boolean = true,
    val json: Json = DEFAULT_JSON,
) {
    companion object {
        /**
         * 缺省 JSON 策略：宽容未知字段、不发送 null、显式开启 default values。
         * 与现有 application 模块（PrivchatServiceClient / DefaultHttpClient）对齐。
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * 把 [NetonHttpClientConfig] 应用到 ktor [HttpClientConfig]——通用部分。
 *
 * 平台 engine 安装由 [platformHttpClient] 这个 expect/actual 完成；本函数
 * 只处理跨平台的 plugin / 全局开关。
 */
internal fun HttpClientConfig<*>.applyNetonDefaults(config: NetonHttpClientConfig) {
    expectSuccess = config.expectSuccess
    install(HttpTimeout) {
        connectTimeoutMillis = config.connectTimeoutMs
        requestTimeoutMillis = config.requestTimeoutMs
        socketTimeoutMillis = config.socketTimeoutMs
    }
    if (config.installJson) {
        install(ContentNegotiation) {
            ktorJson(config.json)
        }
    }
}

/**
 * 平台 engine 工厂——在每个 native target 的 source set 里 actual 实现：
 * - macosArm64 / macosX64 → Darwin
 * - linuxX64 / linuxArm64 → CIO
 * - mingwX64 → WinHttp
 *
 * `applyConfig` lambda 由 [newNetonHttpClient] 传入，已经把 [NetonHttpClientConfig]
 * 的通用部分（timeout / ContentNegotiation / expectSuccess）apply 到 ktor config 上。
 * actual 实现只需要 `HttpClient(<EnginePerPlatform>) { applyConfig() }`。
 */
internal expect fun platformHttpClient(applyConfig: HttpClientConfig<*>.() -> Unit): HttpClient
