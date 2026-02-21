package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("roles")
data class Role(
    @Id val id: Long?,
    val code: String,           // admin, editor, viewer
    val name: String,           // 管理员、编辑、查看者
    val description: String?,
    @CreatedAt val createdAt: Long = 0,
    @UpdatedAt val updatedAt: Long = 0
)
