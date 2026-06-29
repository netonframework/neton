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
    var engine: HttpEngine = HttpEngine.KTOR
    var converterRegistry: ParamConverterRegistry? = null
    var corsConfig: CorsConfig? = null
}

enum class HttpEngine {
    KTOR,
    HYPER4K;

    companion object {
        fun parse(value: String): HttpEngine = when (value.trim().lowercase()) {
            "ktor", "cio" -> KTOR
            "hyper4k", "hyper" -> HYPER4K
            else -> throw IllegalArgumentException("Unsupported HTTP engine: $value")
        }
    }
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
