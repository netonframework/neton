package neton.security.identity

/**
 * Identity 的默认实现，供 Mock/JWT/Session Authenticator 使用
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 */
data class IdentityUser(
    override val userId: UserId,
    override val roles: Set<String> = emptySet(),
    override val permissions: Set<String> = emptySet()
) : Identity
