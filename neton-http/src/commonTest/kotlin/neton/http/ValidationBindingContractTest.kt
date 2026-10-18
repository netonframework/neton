package neton.http

import kotlinx.serialization.json.Json
import neton.core.http.ApiEnvelope
import neton.core.http.MACHINE_CODE_REGEX
import neton.core.http.NetonErrorCode
import neton.core.http.NetonErrorMessage
import neton.core.http.ValidationCode
import neton.core.http.ValidationError
import neton.core.http.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 参数绑定 + 校验契约快测（spec ERROR_CODE_SPEC「消息即机器码」）。
 *
 * 信封 `message` 与字段 `code`/`message` 全是机器码；约束值走 `params`。KSP 生成层的对应断言见
 * neton-ksp `ErrorMachineCodeContractTest`。
 */
class ValidationBindingContractTest {

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun envelope(errors: List<ValidationError>): String {
        val ex = ValidationException(errors)
        return json.encodeToString(ApiEnvelope.serializer(), ApiEnvelope.error(NetonErrorCode.INVALID_PARAMS, ex.message, ex.errors))
    }

    @Test
    fun validationExceptionTopLevelMessageIsValidationFailed() {
        assertEquals(NetonErrorMessage.VALIDATION_FAILED, ValidationException(emptyList()).message)
        assertEquals("VALIDATION_FAILED", NetonErrorMessage.VALIDATION_FAILED)
    }

    @Test
    fun fieldErrorOfUsesTheCodeAsMessage() {
        val e = ValidationError.of("keyword", ValidationCode.REQUIRED)
        assertEquals("keyword", e.path)
        assertEquals("REQUIRED", e.code)
        assertEquals("REQUIRED", e.message)
        assertEquals(null, e.params)
    }

    @Test
    fun missingRequiredSerializesWithoutParams() {
        assertEquals(
            """{"code":10100,"message":"VALIDATION_FAILED","data":null,"errors":[{"field":"q","message":"REQUIRED","code":"REQUIRED"}]}""",
            envelope(listOf(ValidationError.of("q", ValidationCode.REQUIRED))),
        )
    }

    @Test
    fun sizeViolationCarriesStructuredParams() {
        assertEquals(
            """{"code":10100,"message":"VALIDATION_FAILED","data":null,"errors":[{"field":"title","message":"TOO_SHORT","code":"TOO_SHORT","params":{"min":2,"max":32}}]}""",
            envelope(listOf(ValidationError.of("title", ValidationCode.TOO_SHORT, mapOf("min" to 2L, "max" to 32L)))),
        )
    }

    @Test
    fun emptyParamsAreDropped() {
        assertEquals(null, ValidationError.of("x", ValidationCode.INVALID_FORMAT, emptyMap()).params)
    }

    @Test
    fun builtInCodesAreMachineCodes() {
        val codes = listOf(
            ValidationCode.REQUIRED, ValidationCode.TOO_SHORT, ValidationCode.TOO_LONG,
            ValidationCode.TOO_FEW_ITEMS, ValidationCode.TOO_MANY_ITEMS, ValidationCode.TOO_SMALL,
            ValidationCode.TOO_LARGE, ValidationCode.INVALID_FORMAT, ValidationCode.INVALID_EMAIL,
            ValidationCode.INVALID_JSON, ValidationCode.INVALID_INTEGER, ValidationCode.INVALID_NUMBER,
            ValidationCode.INVALID_BOOLEAN, ValidationCode.INVALID_VALUE,
            NetonErrorMessage.VALIDATION_FAILED, NetonErrorMessage.INVALID_PARAMS, NetonErrorMessage.INVALID_JSON,
            NetonErrorMessage.UNSUPPORTED_MEDIA_TYPE, NetonErrorMessage.PAYLOAD_TOO_LARGE, NetonErrorMessage.REQUEST_TIMEOUT,
            NetonErrorMessage.ROUTE_NOT_FOUND, NetonErrorMessage.METHOD_NOT_ALLOWED, NetonErrorMessage.RESOURCE_NOT_FOUND,
            NetonErrorMessage.AUTH_REQUIRED, NetonErrorMessage.PERMISSION_DENIED, NetonErrorMessage.CORS_NOT_ENABLED,
            NetonErrorMessage.ORIGIN_REQUIRED, NetonErrorMessage.ORIGIN_NOT_ALLOWED, NetonErrorMessage.RATE_LIMITED,
            NetonErrorMessage.LOCK_NOT_ACQUIRED, NetonErrorMessage.INTERNAL_ERROR, NetonErrorMessage.SERVICE_UNAVAILABLE,
            NetonErrorMessage.TIMEOUT,
        )
        codes.forEach { assertTrue(MACHINE_CODE_REGEX.matches(it), it) }
        listOf("Validation failed", "must not be blank", "Not_Found", "_X", "rate_limited").forEach {
            assertTrue(!MACHINE_CODE_REGEX.matches(it), it)
        }
    }
}
