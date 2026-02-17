package controller.admin.user

import dto.PageResponse
import dto.UserVO
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Query
import service.UserService

@Controller("/user")
class UserController(private val userService: UserService) {

    @Get("/page")
    @Permission("system:user:page")
    suspend fun page(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("username") username: String? = null,
        @Query("status") status: Int? = null
    ): PageResponse<UserVO> {
        return userService.page(page, size, username, status)
    }
}
