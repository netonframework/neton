package neton.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import neton.core.interfaces.Identity

/**
 * 身份验证器接口 - 专注于验证用户身份。
 *
 * **返回值契约（冻结，不得违反）：**
 * - 返回 [Identity]  → 认证成功，后续 Guard 使用该 Identity 决策
 * - 返回 `null`      → 认证失败（凭据缺失或无效），框架作为匿名请求继续处理
 * - 抛出异常         → 系统级错误（如 Redis 不可用），框架返回 500
 *
 * 禁止用 throw 表达"用户未登录"，应一律返回 null。
 *
 * **[name] 必须全局唯一**，[SecurityBuilder] 注册时会校验，重复注册将 fail-fast。
 */
interface Authenticator {
    /**
     * 执行身份验证。
     * @return 认证成功返回 [Identity]；凭据缺失/无效返回 null；系统错误才允许抛异常
     */
    suspend fun authenticate(context: RequestContext): Identity?

    /**
     * 认证器名称，必须全局唯一（如 "jwt" / "basic" / "my-custom"）
     */
    val name: String
}

/**
 * HTTP Basic 认证器。
 * 解析 "Authorization: Basic <base64(user:pass)>" 头，将用户名和密码交给 [userProvider] 验证。
 * 是否接受空用户名/密码由 [userProvider] 决定，框架不做额外假设。
 */
@OptIn(ExperimentalEncodingApi::class)
class BasicAuthenticator(
    private val userProvider: suspend (username: String, password: String) -> Identity?
) : Authenticator {
    override val name = "basic"

    override suspend fun authenticate(context: RequestContext): Identity? {
        val authHeader = context.headers["Authorization"] ?: return null
        // RFC 7617: auth-scheme comparison is case-insensitive
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return null

        val decoded = try {
            Base64.decode(authHeader.substring(6).trim()).decodeToString()
        } catch (_: Exception) {
            return null
        }
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        val username = decoded.substring(0, colon)
        val password = decoded.substring(colon + 1)
        return userProvider(username, password)
    }
}

/**
 * 模拟认证器 - 开发阶段使用，返回固定用户
 */
class MockAuthenticator(
    private val mockIdentity: Identity = object : Identity {
        override val id: String = "mock-user"
        override val roles: Set<String> = setOf("user")
        override val permissions: Set<String> = emptySet()
    }
) : Authenticator {
    override val name = "mock"

    override suspend fun authenticate(context: RequestContext): Identity = mockIdentity
}

/**
 * 匿名认证器 - 永远返回 null，用于公开路由
 */
class AnonymousAuthenticator : Authenticator {
    override val name = "anonymous"

    override suspend fun authenticate(context: RequestContext): Identity? = null
}
