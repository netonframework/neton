package controller.admin.auth

import controller.admin.auth.dto.LoginRequest
import controller.admin.auth.dto.LoginResponse
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Controller
import neton.core.annotations.Post
import neton.logging.Logger
import service.AuthService

@Controller("/auth")
class AuthController(
    private val log: Logger,
    private val authService: AuthService
) {

    @Post("/login")
    @AllowAnonymous
    suspend fun login(@neton.core.annotations.Body req: LoginRequest): LoginResponse {
        return authService.login(req)
    }
}
