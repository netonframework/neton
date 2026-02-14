package neton.core.component

import neton.core.http.ParamConverter
import neton.core.http.ParamConverterRegistry
import kotlin.reflect.KClass

/**
 * HTTP install DSL 的配置对象
 */
class HttpConfig {
    var port: Int = 8080
    var converterRegistry: ParamConverterRegistry? = null
}

/**
 * converters { register(UUID::class, UuidConverter) }
 */
fun HttpConfig.converters(block: ParamConverterRegistry.() -> Unit) {
    val reg = converterRegistry ?: neton.core.http.DefaultParamConverterRegistry()
    if (converterRegistry == null) converterRegistry = reg
    reg.block()
}
