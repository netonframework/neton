package neton.core.http

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
 *
 * 序列化默认经 [kotlinx.serialization]；neton-http 对常见值形态（null/String/数字/Boolean/Map/Iterable）
 * 提供字节级等价的快速写入路径（契约测试锁定等价性），快速路径覆盖不到的值自动回退到
 * kotlinx.serialization，禁止未经契约测试的手工拼接。
 */
@Serializable
data class ApiEnvelope(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
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
    }
}
