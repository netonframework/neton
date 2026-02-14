package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id

@Serializable
@Table("users")
data class User(
    @Id val id: Long?,
    val name: String,
    val email: String,
    val status: Int,
    val age: Int
)
