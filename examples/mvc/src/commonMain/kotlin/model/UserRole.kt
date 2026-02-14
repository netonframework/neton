package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id

@Serializable
@Table("user_roles")
data class UserRole(
    @Id val id: Long?,
    val userId: Long,
    val roleId: Long
)
