package neton.core.http

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 统一 HTTP 响应信封（spec SERVICE_RESPONSE_ENVELOPE_SPEC §2）。
 *
 * 所有 HTTP 接口必须用此结构序列化响应：
 *
 * ```json
 * { "code": 0, "message": "OK", "data": { ... } }
 * ```
 *
 * - `code`: `protocol::ErrorCode` 数字（spec ERROR_CODE_SPEC），`0` 成功，非 0 错误
 * - `message`: 简短描述（错误时携带可读信息，成功时通常为 `"OK"`）
 * - `data`: 业务数据；错误时**必须** `null`
 * - `errors`: 仅错误时可选出现，字段级错误列表 `[{ "field", "message", "code" }]`（参数校验、业务逐字段拒绝）；
 *   没有字段错误时不输出这个键，成功响应永远不带它
 *
 * 序列化默认经 [kotlinx.serialization]；neton-http 对常见值形态（null/String/数字/Boolean/Map/Iterable）
 * 提供字节级等价的快速写入路径（契约测试锁定等价性），快速路径覆盖不到的值自动回退到
 * kotlinx.serialization，禁止未经契约测试的手工拼接。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ApiEnvelope(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val errors: List<ApiFieldError>? = null,
) {
    companion object {
        /** 成功，无数据。 */
        fun ok(): ApiEnvelope = ApiEnvelope(NetonErrorCode.OK, "OK", JsonNull)

        /** 成功，携带 JSON 数据。 */
        fun ok(data: JsonElement?): ApiEnvelope =
            ApiEnvelope(NetonErrorCode.OK, "OK", data ?: JsonNull)

        /** 错误。 */
        fun error(code: Int, message: String): ApiEnvelope =
            ApiEnvelope(code, message, null)

        /** 错误，携带字段级错误；列表为空时与 [error] 相同。 */
        fun error(code: Int, message: String, errors: List<ValidationError>): ApiEnvelope =
            ApiEnvelope(code, message, null, errors.takeIf { it.isNotEmpty() }?.map { ApiFieldError(it.path, it.message, it.code) })
    }
}

/** 字段级错误的对外形态：`field` 为字段路径（如 `body[2].text`），`code` 为机器码（可空）。 */
@Serializable
data class ApiFieldError(
    val field: String,
    val message: String,
    val code: String? = null,
)
