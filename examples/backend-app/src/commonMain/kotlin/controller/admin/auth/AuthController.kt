package controller.admin.auth

import controller.admin.auth.dto.LoginRequest
import controller.admin.auth.dto.LoginResponse
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Post
import neton.logging.Logger
import logic.AuthLogic

/**
 * 认证 Controller（NetonSQL v2 架构示例）
 *
 * 架构层级：Controller → Logic → Table → DbContext
 */
@Controller("/auth")
class AuthController(
    private val log: Logger,
    private val authLogic: AuthLogic
) {

    @Post("/login")
    @AllowAnonymous
    suspend fun login(@neton.core.annotations.Body req: LoginRequest): LoginResponse {
        return authLogic.login(req)
    }
}
