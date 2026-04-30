package neton.core.http

/**
 * Neton framework 用到的 error codes（与 server `protocol::ErrorCode` 对齐 / spec ERROR_CODE_SPEC）。
 *
 * **完整 error code 体系（300+ 条目）由 spec ERROR_CODE_SPEC 定义**；framework 这里只声明
 * 自己需要 throw 的核心条目。业务模块自行扩展业务段（20000+），如：
 *
 * ```kotlin
 * object MemberErrorCode {
 *     const val MOBILE_ALREADY_BOUND = 21000
 *     const val SMS_CODE_EXPIRED = 21001
 * }
 *
 * throw HttpException(MemberErrorCode.MOBILE_ALREADY_BOUND, "mobile already bound")
 * ```
 *
 * 段位规则（spec ERROR_CODE_SPEC §2）：
 * - `0` 成功
 * - `1-999` 系统级
 * - `100-199` 协议层
 * - `200-299` 版本兼容
 * - `10000-10099` 通用 - 认证
 * - `10100-10199` 通用 - 参数
 * - `10200-10299` 通用 - 资源
 * - `10300-10399` 通用 - 限流
 * - `20000-65535` 业务段（按子系统再细分）
 */
object NetonErrorCode {

    // ── 成功 ──
    const val OK = 0

    // ── System (1-999) ──
    const val SYSTEM_ERROR = 1
    const val SYSTEM_BUSY = 2
    const val SERVICE_UNAVAILABLE = 3
    const val INTERNAL_ERROR = 4
    const val TIMEOUT = 5
    const val DATABASE_ERROR = 7
    const val CACHE_ERROR = 8
    const val NETWORK_ERROR = 9

    // ── Protocol (100-199) ──
    const val PROTOCOL_ERROR = 100
    const val UNSUPPORTED_PROTOCOL = 101
    const val INVALID_PACKET = 102
    const val PACKET_TOO_LARGE = 103
    const val ENCODING_ERROR = 104
    const val DECODING_ERROR = 105

    // ── Common - Auth (10000-10099) ──
    const val AUTH_REQUIRED = 10000
    const val INVALID_TOKEN = 10001
    const val TOKEN_EXPIRED = 10002
    const val TOKEN_REVOKED = 10003
    const val PERMISSION_DENIED = 10004
    const val SESSION_EXPIRED = 10005
    const val SESSION_NOT_FOUND = 10006
    const val USER_BANNED = 10007
    const val IP_NOT_ALLOWED = 10008
    const val REFRESH_TOKEN_EXPIRED = 10009
    const val REFRESH_TOKEN_REVOKED = 10010

    // ── Common - Params (10100-10199) ──
    const val INVALID_PARAMS = 10100
    const val MISSING_REQUIRED_PARAM = 10101
    const val INVALID_PARAM_TYPE = 10102
    const val PARAM_OUT_OF_RANGE = 10103
    const val INVALID_FORMAT = 10104
    const val INVALID_JSON = 10105
    const val PAYLOAD_TOO_LARGE = 10106

    // ── Common - Resource (10200-10299) ──
    const val OPERATION_NOT_ALLOWED = 10200
    const val RESOURCE_NOT_FOUND = 10201
    const val RESOURCE_ALREADY_EXISTS = 10202
    const val RESOURCE_DELETED = 10203
    const val DUPLICATE_OPERATION = 10204
    const val OPERATION_CONFLICT = 10205

    // ── Common - Rate limit (10300-10399) ──
    const val RATE_LIMIT_EXCEEDED = 10300
    const val DAILY_QUOTA_EXCEEDED = 10301
    const val MONTHLY_QUOTA_EXCEEDED = 10302
    const val CONCURRENT_LIMIT_EXCEEDED = 10303
}
