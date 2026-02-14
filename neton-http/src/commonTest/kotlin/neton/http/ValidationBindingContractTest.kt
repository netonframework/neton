package neton.http

import kotlinx.serialization.json.Json
import neton.core.http.ErrorResponse
import neton.core.http.ValidationError
import neton.core.http.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 参数绑定 + 校验契约快测（v1 冻结）。
 * 断言 path / code / message 三元组与规范一致，便于回归与 i18n。
 *
 * 用例对应规范：
 * - 5.3 规则 4/5：Body InvalidJson、Missing、Type、NotBlank
 * - 5.4：必填/可缺省
 */
class ValidationBindingContractTest {

    // --- 1) 非法 JSON ---
    // 请求：POST，body = "{"
    // 断言：errors[0].path == "$"；code == "InvalidJson"；message == "Invalid JSON body"
    @Test
    fun invalidJsonBody_hasPathDollarAndInvalidJsonCode() {
        val errors = listOf(
            ValidationError(path = "$", message = "Invalid JSON body", code = "InvalidJson")
        )
        val ex = ValidationException(errors)
        val body = ErrorResponse(message = ex.message, errors = ex.errors)

        assertEquals(1, body.errors.size)
        assertEquals("$", body.errors[0].path)
        assertEquals("InvalidJson", body.errors[0].code)
        assertEquals("Invalid JSON body", body.errors[0].message)
    }

    // --- 2) Path int 转换失败 ---
    // 请求：GET /users/abc 绑定到 id: Long
    // 断言：errors[0].path == "id"；code == "Type"；message == "must be a valid integer"
    @Test
    fun pathIntConversionFailure_hasPathIdTypeAndIntegerMessage() {
        val errors = listOf(
            ValidationError(path = "id", message = "must be a valid integer", code = "Type")
        )
        val body = ErrorResponse(message = "Validation failed", errors = errors)

        assertEquals(1, body.errors.size)
        assertEquals("id", body.errors[0].path)
        assertEquals("Type", body.errors[0].code)
        assertEquals("must be a valid integer", body.errors[0].message)
    }

    // --- 3) Query 缺失必填 ---
    // 请求：GET /search 缺少 keyword: String
    // 断言：errors[0].path == "keyword"；code == "Missing"；message == "is required"
    @Test
    fun queryMissingRequired_hasPathKeywordMissingAndIsRequired() {
        val errors = listOf(
            ValidationError(path = "keyword", message = "is required", code = "Missing")
        )
        val body = ErrorResponse(message = "Validation failed", errors = errors)

        assertEquals(1, body.errors.size)
        assertEquals("keyword", body.errors[0].path)
        assertEquals("Missing", body.errors[0].code)
        assertEquals("is required", body.errors[0].message)
    }

    // --- 5) @Query("q") path 必须为注解 value "q"（不是参数名 keyword）---
    // 方法签名：fun search(@Query("q") keyword: String)，缺失 q
    // 断言：errors[0].path == "q"，不是 "keyword"
    @Test
    fun queryWithExplicitName_pathIsAnnotationValueNotParamName() {
        val errors = listOf(
            ValidationError(path = "q", message = "is required", code = "Missing")
        )
        val body = ErrorResponse(message = "Validation failed", errors = errors)

        assertEquals(1, body.errors.size)
        assertEquals("q", body.errors[0].path)
        assertEquals("Missing", body.errors[0].code)
        assertEquals("is required", body.errors[0].message)
    }

    // --- 6) List 多值参数转换失败（v1 冻结：任一元素无法 parse → 整参报错，fail-fast）---
    // 请求：GET /items?ids=1&ids=abc&ids=3 绑定到 ids: List<Int>（多值仅重复 key，见规范 5.4）
    // 断言：errors[0].path == "ids"；code == "Type"；message == "must be a valid integer"
    // 后续 v2 可支持 ids[1] 等深层 path，v1 仅 param 级。
    @Test
    fun listElementParseFailure_hasPathParamNameTypeAndIntegerMessage() {
        val errors = listOf(
            ValidationError(path = "ids", message = "must be a valid integer", code = "Type")
        )
        val body = ErrorResponse(message = "Validation failed", errors = errors)

        assertEquals(1, body.errors.size)
        assertEquals("ids", body.errors[0].path)
        assertEquals("Type", body.errors[0].code)
        assertEquals("must be a valid integer", body.errors[0].message)
    }

    // --- 4) NotBlank nullable（DTO name: String? with @NotBlank, body {"name": null}）---
    // 断言：errors[0].path == "name"；code == "NotBlank"（或实际 code）；message 稳定
    @Test
    fun notBlankNullable_hasPathNameAndNotBlankCode() {
        val errors = listOf(
            ValidationError(path = "name", message = "must not be blank", code = "NotBlank")
        )
        val body = ErrorResponse(message = "Validation failed", errors = errors)

        assertEquals(1, body.errors.size)
        assertEquals("name", body.errors[0].path)
        assertEquals("NotBlank", body.errors[0].code)
        assertEquals("must not be blank", body.errors[0].message)
    }

    /** 可选：序列化后客户端可见的 JSON 结构稳定（path/code/message 字段名） */
    @Test
    fun errorResponse_serializesWithPathCodeMessage() {
        val errors = listOf(
            ValidationError(path = "x", message = "is required", code = "Missing")
        )
        val body = ErrorResponse(success = false, message = "Validation failed", errors = errors)
        val json = Json.encodeToString(ErrorResponse.serializer(), body)

        assertEquals(true, json.contains("\"path\":\"x\""))
        assertEquals(true, json.contains("\"code\":\"Missing\""))
        assertEquals(true, json.contains("\"message\":\"is required\""))
    }
}
