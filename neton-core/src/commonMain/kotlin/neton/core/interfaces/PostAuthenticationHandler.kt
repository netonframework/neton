package neton.core.interfaces

import neton.core.http.HttpContext

/**
 * 认证成功后的统一挂点。安全管道在 identity 建立并写入 [SecurityAttributes.IDENTITY] 之后调用一次，
 * HTTP 与 WebSocket 升级握手共用同一条管道，因此这里能覆盖所有带会话的入口。
 *
 * 典型用途：cookie 会话滑动续期——剩余有效期不足阈值时重签令牌并写 Set-Cookie
 * （升级路径的 Set-Cookie 由分发器合并进 101 响应）。
 *
 * 通过 `ctx.bind(PostAuthenticationHandler::class, impl)` 注册；每次认证成功查一次，
 * 未注册或传输层没有应用上下文时自然跳过。**实现不得抛异常**：续期失败不能拦住一个
 * 已经通过认证的请求；请自行 catch 并记日志（协程取消必须原样重抛）。
 */
fun interface PostAuthenticationHandler {
    suspend fun onAuthenticated(context: HttpContext, identity: Identity, requestContext: RequestContext)
}
