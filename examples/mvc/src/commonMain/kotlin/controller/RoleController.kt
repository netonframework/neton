package controller

import model.Role
import model.RoleTable
import neton.core.annotations.*
import neton.core.http.*
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/roles")
@Log
class RoleController(private val log: Logger) {

    @Get
    suspend fun all(): List<Role> =
        RoleTable.findAll()

    @Get("/{id}")
    suspend fun get(id: Long): Role? {
        log.info("role.get", mapOf("roleId" to id))
        return RoleTable.get(id)
    }

    @Post
    suspend fun create(@Body role: Role): Role =
        RoleTable.save(role)

    @Put("/{id}")
    suspend fun update(id: Long, @Body role: Role): Role {
        val current = RoleTable.get(id) ?: throw NotFoundException("Role $id not found")
        val updated = current.copy(name = role.name)
        RoleTable.update(updated)
        return updated
    }

    @Delete("/{id}")
    suspend fun delete(id: Long) {
        RoleTable.destroy(id)
    }
}
