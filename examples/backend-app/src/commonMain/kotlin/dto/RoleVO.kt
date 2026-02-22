package dto

import kotlinx.serialization.Serializable

@Serializable
data class RoleVO(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?
)
