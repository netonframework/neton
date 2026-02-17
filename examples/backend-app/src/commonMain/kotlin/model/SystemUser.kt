package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.SoftDelete
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("system_users")
data class SystemUser(
    @Id val id: Long?,
    val username: String,
    val passwordHash: String,
    val nickname: String,
    val status: Int = 0,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: Long = 0,
    @UpdatedAt val updatedAt: Long = 0
)
