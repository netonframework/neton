package logic

import model.UserRole
import model.UserRoleTable

class UserRoleLogic {

    suspend fun all(): List<UserRole> =
        UserRoleTable.findAll()

    suspend fun get(id: Long): UserRole? =
        UserRoleTable.get(id)

    suspend fun create(userRole: UserRole): UserRole =
        UserRoleTable.insert(userRole)

    suspend fun delete(id: Long) {
        UserRoleTable.destroy(id)
    }
}
