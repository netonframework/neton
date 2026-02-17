package controller.admin.auth.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val accessToken: String,
    val userId: Long,
    val username: String,
    val nickname: String
)
