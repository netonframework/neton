package neton.core.component

import neton.core.http.ParamConverter
import neton.core.http.ParamConverterRegistry
import kotlin.reflect.KClass

/**
 * CORS 配置
 */
class CorsConfig {
    var allowedOrigins: List<String> = listOf("*")
    var allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
    var allowedHeaders: List<String> = listOf("*")
    var allowCredentials: Boolean = false
    var maxAgeSeconds: Long = 3600
}

/**
 * HTTP install DSL 的配置对象
 */
class HttpConfig {
    var port: Int = 8080
    var converterRegistry: ParamConverterRegistry? = null
    var corsConfig: CorsConfig? = null

    /**
     * TLS for the primary listener. Null serves cleartext. application.conf's
     * `[http.tls]` takes precedence over this when both are present.
     */
    var tls: neton.core.http.adapter.TlsSettings? = null
}

/**
 * tls { certificatePath = "..."; privateKeyPath = "..." } inside `http { }`.
 * ALPN defaults to HTTP/1.1; set `alpnProtocols = listOf("h2", "http/1.1")` for
 * HTTP/2 over TLS.
 */
fun HttpConfig.tls(block: TlsConfigBuilder.() -> Unit) {
    val b = TlsConfigBuilder().apply(block)
    tls = neton.core.http.adapter.TlsSettings(
        certificatePath = requireNotNull(b.certificatePath) { "tls { } requires certificatePath" },
        privateKeyPath = requireNotNull(b.privateKeyPath) { "tls { } requires privateKeyPath" },
        alpnProtocols = b.alpnProtocols,
    )
}

class TlsConfigBuilder {
    var certificatePath: String? = null
    var privateKeyPath: String? = null
    var alpnProtocols: List<String> = listOf("http/1.1")
}

/**
 * converters { register(UUID::class, UuidConverter) }
 */
fun HttpConfig.converters(block: ParamConverterRegistry.() -> Unit) {
    val reg = converterRegistry ?: neton.core.http.DefaultParamConverterRegistry()
    if (converterRegistry == null) converterRegistry = reg
    reg.block()
}

/**
 * cors { allowedOrigins = listOf("http://localhost:3000") }
 */
fun HttpConfig.cors(block: CorsConfig.() -> Unit) {
    corsConfig = CorsConfig().apply(block)
}
