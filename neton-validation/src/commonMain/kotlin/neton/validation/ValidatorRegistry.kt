package neton.validation

import kotlin.reflect.KClass

/**
 * 校验器注册表（内部使用，由 KSP 生成的 GeneratedValidatorRegistry 提供默认实现）。
 */
interface ValidatorRegistry {
    fun <T : Any> get(type: KClass<T>): Validator<T>?
}
