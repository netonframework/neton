package neton.validation

import neton.core.http.ValidationError

/**
 * 校验器接口（极简，不搞 SPI）。
 * 由 KSP 生成实现，或通过 Konform 封装使用。
 */
fun interface Validator<T : Any> {
    fun validate(value: T): List<ValidationError>
}
