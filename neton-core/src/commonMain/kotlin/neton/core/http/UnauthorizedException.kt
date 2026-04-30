package neton.core.http

/**
 * 路由层鉴权失败异常（spec ERROR_CODE_SPEC：`AUTH_REQUIRED = 10000`）。
 *
 * 由 KSP 生成的 controller invocation wrapper 在 [neton.core.interfaces.Identity] 注入失败时抛出
 * （即 controller handler 声明 `identity: Identity` 但请求未带有效 token）。
 *
 * 由 framework 全局异常处理器捕获 → 返 HTTP 401 + envelope `{code: 10000, message: ...}`。
 *
 * 业务侧需要更细错误码（如 `INVALID_TOKEN=10001` / `TOKEN_EXPIRED=10002`）请直接 throw
 * `HttpException(NetonErrorCode.INVALID_TOKEN, ...)`。
 */
class UnauthorizedException(
    message: String = "Authentication required",
) : HttpException(NetonErrorCode.AUTH_REQUIRED, message)
