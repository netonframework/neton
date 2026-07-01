package logic

import model.Role
import model.RoleTable
import neton.core.http.NotFoundException

class RoleLogic {

    suspend fun all(): List<Role> =
        RoleTable.findAll()

    suspend fun get(id: Long): Role? =
        RoleTable.get(id)

    suspend fun create(role: Role): Role =
        RoleTable.insert(role)

    suspend fun update(id: Long, role: Role): Role {
        val current = RoleTable.get(id) ?: throw NotFoundException("Role $id not found")
        val updated = current.copy(name = role.name)
        RoleTable.update(updated)
        return updated
    }

    suspend fun delete(id: Long) {
        RoleTable.destroy(id)
    }
}
