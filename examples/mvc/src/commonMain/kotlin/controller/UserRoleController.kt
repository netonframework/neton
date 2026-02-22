package controller

import logic.UserRoleLogic
import model.UserRole
import neton.core.annotations.*
import neton.core.http.*
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/user-roles")
@Log
class UserRoleController(
    private val log: Logger,
    private val userRoleLogic: UserRoleLogic = UserRoleLogic()
) {

    @Get
    suspend fun all(): List<UserRole> =
        userRoleLogic.all()

    @Get("/{id}")
    suspend fun get(id: Long): UserRole? {
        log.info("userRole.get", mapOf("userRoleId" to id))
        return userRoleLogic.get(id)
    }

    @Post
    suspend fun create(@Body userRole: UserRole): UserRole =
        userRoleLogic.create(userRole)

    @Delete("/{id}")
    suspend fun delete(id: Long) =
        userRoleLogic.delete(id)
}
