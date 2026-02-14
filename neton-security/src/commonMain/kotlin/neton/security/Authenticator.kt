package neton.security

/**
 * 身份验证器接口 - 专注于验证用户身份
 */
interface Authenticator {
    /**
     * 执行身份验证
     * @param context 请求上下文
     * @return 验证成功返回 Principal，失败返回 null
     */
    suspend fun authenticate(context: RequestContext): Principal?
    
    /**
     * 认证器名称
     */
    val name: String
}

/**
 * 会话认证器
 */
class SessionAuthenticator(
    private val sessionKey: String = "user_id"
) : Authenticator {
    override val name = "session"
    
    override suspend fun authenticate(context: RequestContext): Principal? {
        val sessionId = context.getSessionId() ?: return null
        
        // TODO: 实现会话认证逻辑
        // 这里需要等待 Session 支持
        return null
    }
}

/**
 * JWT 认证器
 */
class JwtAuthenticator(
    private val secretKey: String,
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer "
) : Authenticator {
    override val name = "jwt"
    
    override suspend fun authenticate(context: RequestContext): Principal? {
        val authHeader = context.headers[headerName] ?: return null
        val token = if (authHeader.startsWith(tokenPrefix)) {
            authHeader.substring(tokenPrefix.length)
        } else {
            authHeader
        }
        
        // TODO: 实现 JWT 验证逻辑
        // 这里需要 JWT 库支持
        return null
    }
}

/**
 * Basic 认证器
 */
class BasicAuthenticator(
    private val userProvider: suspend (username: String, password: String) -> Principal?
) : Authenticator {
    override val name = "basic"
    
    override suspend fun authenticate(context: RequestContext): Principal? {
        val authHeader = context.headers["Authorization"] ?: return null
        if (!authHeader.startsWith("Basic ")) return null
        
        try {
            // TODO: 实现 Base64 解码和认证逻辑
            // 这里需要 Base64 解码支持
            return null
        } catch (e: Exception) {
            return null
        }
    }
}

/**
 * 模拟认证器 - 开发阶段使用，返回固定用户
 */
class MockAuthenticator(
    private val mockUser: Principal = UserPrincipal("mock-user", listOf("user"))
) : Authenticator {
    override val name = "mock"
    
    override suspend fun authenticate(context: RequestContext): Principal = mockUser
}

/**
 * 匿名认证器 - 永远返回 null，用于公开路由
 */
class AnonymousAuthenticator : Authenticator {
    override val name = "anonymous"
    
    override suspend fun authenticate(context: RequestContext): Principal? = null
} 