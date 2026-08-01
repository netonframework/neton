package neton.validation

import neton.core.http.ValidationError
import neton.validation.internal.DefaultValidatorRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ValidatorRegistry 查找契约。
 *
 * 校验规则本身由 KSP 在编译期生成成 `Validator<T>` 实现（纯 if 判断，见
 * `neton-ksp` 的 ValidatorGenerator），运行时这一层只负责「按类型找到校验器」。
 * 找不到时必须返回 null——由调用方决定是跳过校验还是报错，注册表不能自己吞掉。
 */
class ValidatorRegistryTest {

    private data class CreateUserRequest(val name: String)
    private data class UpdateUserRequest(val name: String)
    private data class UnregisteredRequest(val name: String)

    private val createValidator = Validator<CreateUserRequest> { value ->
        if (value.name.isBlank()) {
            listOf(ValidationError(path = "name", message = "must not be blank", code = "NotBlank"))
        } else {
            emptyList()
        }
    }

    private val updateValidator = Validator<UpdateUserRequest> { emptyList() }

    private val registry = DefaultValidatorRegistry(
        mapOf(
            CreateUserRequest::class to createValidator,
            UpdateUserRequest::class to updateValidator,
        )
    )

    @Test
    fun returnsValidatorRegisteredForTheType() {
        assertSame(createValidator, registry.get(CreateUserRequest::class))
        assertSame(updateValidator, registry.get(UpdateUserRequest::class))
    }

    @Test
    fun returnsNullForUnregisteredType() {
        assertNull(registry.get(UnregisteredRequest::class))
    }

    @Test
    fun emptyRegistryReturnsNull() {
        assertNull(DefaultValidatorRegistry(emptyMap()).get(CreateUserRequest::class))
    }

    @Test
    fun resolvedValidatorReportsFailures() {
        val validator = registry.get(CreateUserRequest::class)!!
        val errors = validator.validate(CreateUserRequest(name = ""))

        assertEquals(1, errors.size)
        assertEquals("name", errors[0].path)
        assertEquals("must not be blank", errors[0].message)
        assertEquals("NotBlank", errors[0].code)
    }

    @Test
    fun resolvedValidatorReportsNoErrorsForValidValue() {
        val validator = registry.get(CreateUserRequest::class)!!
        assertEquals(emptyList(), validator.validate(CreateUserRequest(name = "neton")))
    }
}
