package neton.core.security

/**
 * 认证上下文抽象 - 避免模块间循环依赖
 * 
 * 提供通用的认证上下文接口，供各模块使用
 */
interface AuthenticationContext {
    fun currentUser(): Any?
} 