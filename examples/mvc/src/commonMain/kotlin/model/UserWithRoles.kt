package model

import kotlinx.serialization.Serializable

/**
 * 聚合 DTO：用户 + 角色列表（多表联查结果）
 */
@Serializable
data class UserWithRoles(
    val user: User,
    val roles: List<Role>
)
