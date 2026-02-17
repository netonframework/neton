package dto

import kotlinx.serialization.Serializable

@Serializable
data class UserVO(
    val id: Long,
    val username: String,
    val nickname: String,
    val status: Int,
    val createdAt: Long,
    val updatedAt: Long
)
