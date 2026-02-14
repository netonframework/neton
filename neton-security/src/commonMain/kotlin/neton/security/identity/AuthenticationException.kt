package neton.security.identity

/**
 * 认证异常，HTTP 映射为 401
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 * @see Neton-JWT-Authenticator-Spec-v1.md 第六节
 */
class AuthenticationException(
    val code: String,
    override val message: String,
    val path: String = ""
) : RuntimeException("$code: $message (path=$path)")
