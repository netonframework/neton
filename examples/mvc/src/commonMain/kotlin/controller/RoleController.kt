package controller

import logic.RoleLogic
import model.Role
import neton.core.annotations.*
import neton.core.http.*
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/roles")
@Log
class RoleController(
    private val log: Logger,
    private val roleLogic: RoleLogic = RoleLogic()
) {

    @Get
    suspend fun all(): List<Role> =
        roleLogic.all()

    @Get("/{id}")
    suspend fun get(id: Long): Role? {
        log.info("role.get", mapOf("roleId" to id))
        return roleLogic.get(id)
    }

    @Post
    suspend fun create(@Body role: Role): Role =
        roleLogic.create(role)

    @Put("/{id}")
    suspend fun update(id: Long, @Body role: Role): Role =
        roleLogic.update(id, role)

    @Delete("/{id}")
    suspend fun delete(id: Long) =
        roleLogic.delete(id)
}
