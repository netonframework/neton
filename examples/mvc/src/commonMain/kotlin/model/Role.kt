package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id

@Serializable
@Table("roles")
data class Role(
    @Id val id: Long?,
    val name: String
)
