package controller.admin.user

import dto.PageResponse
import dto.UserVO
import dto.UserWithRolesVO
import neton.core.annotations.*
import logic.UserLogic

/**
 * 用户管理 Controller（NetonSQL v1 架构示例）
 *
 * 架构层级：Controller → Logic → Table → DbContext
 */
@Controller("/user")
class UserController(private val userLogic: UserLogic) {

    /**
     * 分页查询用户（Phase 1 单表查询）
     */
    @Get("/page")
    @Permission("system:user:page")
    suspend fun page(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("username") username: String? = null,
        @Query("status") status: Int? = null
    ): PageResponse<UserVO> {
        return userLogic.page(page, size, username, status)
    }

    /**
     * 获取用户详情（含角色列表）- Phase 4 JOIN 查询示例
     */
    @Get("/{id}/with-roles")
    @Permission("system:user:detail")
    suspend fun getUserWithRoles(@PathVariable("id") id: Long): UserWithRolesVO {
        return userLogic.getUserWithRoles(id)
            ?: throw IllegalArgumentException("User not found: $id")
    }

    /**
     * 按角色筛选用户 - Phase 4 JOIN + typed projection 示例
     */
    @Get("/by-role/{roleCode}")
    @Permission("system:user:page")
    suspend fun listByRole(@PathVariable("roleCode") roleCode: String): List<UserVO> {
        return userLogic.listUsersByRole(roleCode)
    }
}
