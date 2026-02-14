package neton.core.http

import kotlin.reflect.KClass

/**
 * 参数转换器 SPI（规范 v1.0.1）
 * 用于将字符串转换为自定义类型（UUID、LocalDate、UserId 等）
 */
interface ParamConverter<T> {
    /**
     * 将字符串转换为目标类型
     * @return 转换后的值，null 表示无法转换
     */
    fun convert(value: String): T?
}

/**
 * 参数转换器注册表
 * 解析优先级：内置转换器 > 用户注册转换器 > 报错 400
 */
interface ParamConverterRegistry {
    fun <T : Any> register(type: KClass<T>, converter: ParamConverter<T>)

    fun <T : Any> getConverter(type: KClass<T>): ParamConverter<T>?
    fun <T : Any> convert(value: String, type: KClass<T>): T?
}

/** inline reified：converters { register(UuidConverter) } */
inline fun <reified T : Any> ParamConverterRegistry.register(converter: ParamConverter<T>) {
    register(T::class, converter)
}

/**
 * 内置字符串转换工具（规范 v1.0.1 必做项）
 * - Boolean：true/false/1/0/on/off 宽松解析
 * - 空字符串：对可空类型→null，非可空→失败
 */
object ParamConverters {
    /**
     * Boolean 宽松解析
     */
    fun parseBoolean(value: String): Boolean? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return null
        return when (v) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> null
        }
    }

    /**
     * 解析为 Int，空字符串返回 null
     */
    fun parseInt(value: String): Int? {
        if (value.isBlank()) return null
        return value.trim().toIntOrNull()
    }

    /**
     * 解析为 Long，空字符串返回 null
     */
    fun parseLong(value: String): Long? {
        if (value.isBlank()) return null
        return value.trim().toLongOrNull()
    }

    /**
     * 解析为 Double，空字符串返回 null
     */
    fun parseDouble(value: String): Double? {
        if (value.isBlank()) return null
        return value.trim().toDoubleOrNull()
    }
}
