package logic

import controller.admin.auth.dto.LoginRequest
import controller.admin.auth.dto.LoginResponse
import model.SystemUser
import model.SystemUserTable
import neton.database.dsl.eq
import neton.logging.Logger
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticatorV1

/**
 * 认证业务逻辑（NetonSQL v2 架构）
 *
 * 架构层级：
 * Controller → Logic → Table → DbContext → Driver
 */
class AuthLogic(
    private val log: Logger,
    private val jwt: JwtAuthenticatorV1
) {

    /**
     * 用户登录（Phase 1 单表查询）
     */
    suspend fun login(req: LoginRequest): LoginResponse {
        val user = SystemUserTable.query {
            where { SystemUser::username eq req.username }
        }.list().firstOrNull()

        if (user == null) {
            log.warn("auth.login.failed", mapOf("username" to req.username, "reason" to "user_not_found"))
            throw IllegalArgumentException("Invalid username or password")
        }

        if (user.deleted != 0) {
            log.warn("auth.login.failed", mapOf("username" to req.username, "reason" to "user_deleted"))
            throw IllegalArgumentException("Invalid username or password")
        }

        if (user.status != 0) {
            log.warn("auth.login.failed", mapOf("username" to req.username, "reason" to "user_disabled"))
            throw IllegalArgumentException("Account is disabled")
        }

        // beta1: 明文密码比较（生产环境应使用 bcrypt/argon2）
        if (user.passwordHash != req.password) {
            log.warn("auth.login.failed", mapOf("username" to req.username, "reason" to "wrong_password"))
            throw IllegalArgumentException("Invalid username or password")
        }

        val token = jwt.createToken(
            userId = UserId(user.id!!.toULong()),
            roles = setOf("admin"),
            permissions = setOf("system:user:page", "system:user:create", "system:user:update"),
            expiresInSeconds = 7200
        )

        log.info("auth.login.success", mapOf("userId" to user.id, "username" to user.username))

        return LoginResponse(
            accessToken = token,
            userId = user.id,
            username = user.username,
            nickname = user.nickname
        )
    }
}
