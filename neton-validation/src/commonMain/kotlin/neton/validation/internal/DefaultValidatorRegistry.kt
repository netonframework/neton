package neton.validation.internal

import kotlin.reflect.KClass
import neton.validation.Validator
import neton.validation.ValidatorRegistry

internal class DefaultValidatorRegistry(
    private val validators: Map<KClass<*>, Validator<*>>
) : ValidatorRegistry {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(type: KClass<T>): Validator<T>? =
        validators[type] as? Validator<T>
}
