package neton.security.identity

/**
 * 当前登录用户的身份抽象
 * 继承 core Identity，新增强类型 userId: UserId
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 */
interface Identity : neton.core.interfaces.Identity {
    val userId: UserId
    override val id: String get() = userId.value.toString()
}
