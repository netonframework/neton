package neton.validation.konform

import neton.core.http.ValidationError
import neton.validation.Validator

/**
 * 将 Konform Validation 封装为 Validator<T>。
 * Konform 不暴露给用户，仅在此处使用。
 */
internal class KonformValidator<T : Any>(
    private val validation: io.konform.validation.Validation<T>
) : Validator<T> {

    override fun validate(value: T): List<ValidationError> {
        val result = validation(value)
        return when (result) {
            is io.konform.validation.Invalid -> result.errors.map {
                ValidationError(
                    path = it.dataPath.toString(),
                    message = it.message,
                    code = null
                )
            }
            else -> emptyList()
        }
    }
}
