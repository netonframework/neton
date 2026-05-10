package neton.http.client

import io.ktor.client.HttpClient

/**
 * Framework-level HTTP **出站** client factory.
 *
 * Neton 自身的 server 能力由 [neton.http.HttpComponent] 提供（入站，Ktor server）；
 * 出站能力（业务模块调外部 HTTP）应该统一通过本工厂取——避免每个 application module
 * 自己 `new HttpClient(Engine)`，造成 timeout / serialization / engine / TLS 策略
 * 在不同模块各种分裂。
 *
 * ## 使用
 *
 * ```kotlin
 * // 默认配置（3s timeout、JSON ContentNegotiation、不抛 4xx/5xx）
 * val client = newNetonHttpClient()
 *
 * // 业务定制
 * val client = newNetonHttpClient(
 *     NetonHttpClientConfig(
 *         requestTimeoutMs = 5_000,
 *         installJson = false,
 *     ),
 * )
 * ```
 *
 * 创建出来的 [HttpClient] 由调用方持有 + 关闭。框架不做单例池——每个业务客户端
 * 持有自己的实例，避免连接池策略相互拖累。
 *
 * ## 平台 engine 选择
 *
 * 由 [platformHttpClient] 的 actual 实现决定，跟业务无关：
 * - macOS（macosArm64 / macosX64）→ Darwin
 * - Linux（linuxX64 / linuxArm64）→ CIO
 * - Windows（mingwX64）→ WinHttp
 *
 * 所有 engine 都已经被 `neton-http` 的 build.gradle.kts 声明为 per-target
 * dependency；调用方**不需要**也**不应该**再自己引 ktor engine。
 */
fun newNetonHttpClient(
    config: NetonHttpClientConfig = NetonHttpClientConfig(),
): HttpClient = platformHttpClient { applyNetonDefaults(config) }
