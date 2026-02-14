package controller

import model.UserRole
import model.UserRoleTable
import neton.core.annotations.*
import neton.core.http.*
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/user-roles")
@Log
class UserRoleController(private val log: Logger) {

    @Get
    suspend fun all(): List<UserRole> =
        UserRoleTable.findAll()

    @Get("/{id}")
    suspend fun get(id: Long): UserRole? {
        log.info("userRole.get", mapOf("userRoleId" to id))
        return UserRoleTable.get(id)
    }

    @Post
    suspend fun create(@Body userRole: UserRole): UserRole =
        UserRoleTable.save(userRole)

    @Delete("/{id}")
    suspend fun delete(id: Long) {
        UserRoleTable.destroy(id)
    }
}
