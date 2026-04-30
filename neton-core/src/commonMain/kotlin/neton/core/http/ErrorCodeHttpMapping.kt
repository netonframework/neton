package neton.core.http

/**
 * Framework 内部用：ErrorCode → HTTP status 粗粒度映射（spec SERVICE_RESPONSE_ENVELOPE_SPEC §3.1）。
 *
 * **业务侧不应该用这个**。客户端判断错误**只看 `body.code`**；HTTP status 仅给 LB / proxy /
 * 监控用，不进入业务分支。
 *
 * 此映射只服务 framework 在写响应时填充 HTTP status header；它的存在不代表 HTTP status
 * 与 ErrorCode 等价，反过来 ErrorCode 是更细的维度。
 */
fun httpStatusForErrorCode(code: Int): HttpStatus = when (code) {
    NetonErrorCode.OK -> HttpStatus.OK

    // System (1-999) → 5xx
    NetonErrorCode.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
    in 1..99 -> HttpStatus.INTERNAL_SERVER_ERROR
    in 100..299 -> HttpStatus.BAD_REQUEST           // 协议 / 版本兼容相关

    // Auth (10000-10099) → 401/403
    NetonErrorCode.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
    NetonErrorCode.IP_NOT_ALLOWED -> HttpStatus.FORBIDDEN
    in 10000..10099 -> HttpStatus.UNAUTHORIZED

    // Params (10100-10199) → 400
    in 10100..10199 -> HttpStatus.BAD_REQUEST

    // Resource (10200-10299) → 404/409
    NetonErrorCode.RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND
    NetonErrorCode.RESOURCE_ALREADY_EXISTS,
    NetonErrorCode.OPERATION_CONFLICT,
    NetonErrorCode.DUPLICATE_OPERATION -> HttpStatus.CONFLICT
    NetonErrorCode.OPERATION_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED
    NetonErrorCode.RESOURCE_DELETED -> HttpStatus.NOT_FOUND
    in 10200..10299 -> HttpStatus.BAD_REQUEST

    // Rate limit (10300-10399) → 429
    in 10300..10399 -> HttpStatus.TOO_MANY_REQUESTS

    // Business (20000+) → 默认 500（业务侧若希望特殊 status，自行用 ktor `respond(status, envelope)`）
    else -> HttpStatus.INTERNAL_SERVER_ERROR
}
