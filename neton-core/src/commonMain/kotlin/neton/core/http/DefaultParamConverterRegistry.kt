package neton.core.http

import kotlin.reflect.KClass

/**
 * 默认参数转换器注册表（规范 v1.0.2）
 * 内置 String/Int/Long/Boolean/Double/Float，支持用户覆盖
 */
class DefaultParamConverterRegistry : ParamConverterRegistry {
    private val converters = mutableMapOf<KClass<*>, ParamConverter<*>>()

    init {
        registerBuiltins()
    }

    private fun registerBuiltins() {
        register(String::class, object : ParamConverter<String> {
            override fun convert(value: String) = value.ifBlank { null }
        })
        register(Int::class, object : ParamConverter<Int> {
            override fun convert(value: String) = ParamConverters.parseInt(value)
        })
        register(Long::class, object : ParamConverter<Long> {
            override fun convert(value: String) = ParamConverters.parseLong(value)
        })
        register(Boolean::class, object : ParamConverter<Boolean> {
            override fun convert(value: String) = ParamConverters.parseBoolean(value)
        })
        register(Double::class, object : ParamConverter<Double> {
            override fun convert(value: String) = ParamConverters.parseDouble(value)
        })
        register(Float::class, object : ParamConverter<Float> {
            override fun convert(value: String) = ParamConverters.parseDouble(value)?.toFloat()
        })
    }

    override fun <T : Any> register(type: KClass<T>, converter: ParamConverter<T>) {
        @Suppress("UNCHECKED_CAST")
        converters[type] = converter as ParamConverter<*>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getConverter(type: KClass<T>): ParamConverter<T>? =
        converters[type] as? ParamConverter<T>

    override fun <T : Any> convert(value: String, type: KClass<T>): T? {
        val converter = getConverter(type) ?: return null
        return converter.convert(value) as? T
    }
}
