package neton.ai.protocol.error

import neton.ai.protocol.ir.WireFormat

/** 错误类别，与 SDK 的 AiError 一一对应，便于双方映射（spec §8）。 */
enum class ErrorKind {
    InvalidRequest, Unauthorized, Forbidden, NotFound, RateLimited, ContextLengthExceeded,
    ContentFilter, Overloaded, ServerError, Timeout, Network, Unknown;

    /** 回退资格：网络、超时、限流、过载、5xx 可以换渠道重试。 */
    val isFallbackEligible: Boolean
        get() = this == RateLimited || this == Overloaded || this == ServerError || this == Timeout || this == Network
}

/**
 * 协议无关的错误。[rawBody] 保留上游原始错误体：同协议原样透传，跨协议时按客户端协议重新渲染。
 * [message] 必须是可以给客户端看的文本，**不得**包含内部异常详情（spec §8）。
 */
data class ProtocolError(
    val kind: ErrorKind,
    val message: String,
    val status: Int? = null,
    /** 上游的错误类型或代码（如 `overloaded_error`、`RESOURCE_EXHAUSTED`）。 */
    val code: String? = null,
    val rawBody: String? = null,
    val sourceFormat: WireFormat? = null,
) {
    companion object {
        fun kindForStatus(status: Int): ErrorKind = when (status) {
            400, 422 -> ErrorKind.InvalidRequest
            401 -> ErrorKind.Unauthorized
            403 -> ErrorKind.Forbidden
            404 -> ErrorKind.NotFound
            408, 504 -> ErrorKind.Timeout
            413 -> ErrorKind.ContextLengthExceeded
            429 -> ErrorKind.RateLimited
            503, 529 -> ErrorKind.Overloaded
            in 500..599 -> ErrorKind.ServerError
            else -> ErrorKind.Unknown
        }

        fun statusFor(kind: ErrorKind): Int = when (kind) {
            ErrorKind.InvalidRequest, ErrorKind.ContextLengthExceeded, ErrorKind.ContentFilter -> 400
            ErrorKind.Unauthorized -> 401
            ErrorKind.Forbidden -> 403
            ErrorKind.NotFound -> 404
            ErrorKind.RateLimited -> 429
            ErrorKind.Overloaded -> 503
            ErrorKind.Timeout -> 504
            ErrorKind.Network -> 502
            ErrorKind.ServerError, ErrorKind.Unknown -> 500
        }
    }
}
