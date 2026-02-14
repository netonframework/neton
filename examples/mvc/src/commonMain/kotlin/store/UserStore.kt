package store

import model.Role
import model.User
import model.UserWithRoles
import neton.database.api.SqlRunner
import neton.database.sqlRunner

/**
 * 聚合 Store：跨表联查（users + user_roles + roles）
 * 遵循 Table/Store 职责边界：Table 单表 CRUD，Store 聚合/JOIN
 */
class UserStore(private val db: SqlRunner = sqlRunner()) : SqlRunner by db {

    suspend fun getWithRoles(userId: Long): UserWithRoles? {
        val sql = """
            SELECT u.id, u.name, u.email, u.status, u.age, r.id AS role_id, r.name AS role_name
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.id = :uid
        """.trimIndent()
        val rows = fetchAll(sql, mapOf("uid" to userId))
        if (rows.isEmpty()) return null
        val first = rows.first()
        val user = User(
            id = first.long("id"),
            name = first.string("name"),
            email = first.string("email"),
            status = first.int("status"),
            age = first.int("age")
        )
        val roles = rows.mapNotNull { r ->
            r.longOrNull("role_id")?.let { Role(it, r.string("role_name")) }
        }.distinctBy { it.id }
        return UserWithRoles(user, roles)
    }
}
