package dto

import kotlinx.serialization.Serializable
import logic.RoleVO

@Serializable
data class UserWithRolesVO(
    val id: Long,
    val username: String,
    val nickname: String,
    val status: Int,
    val roles: List<RoleVO>,
    val createdAt: Long,
    val updatedAt: Long
)
