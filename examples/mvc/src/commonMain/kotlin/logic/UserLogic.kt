package logic

import model.*
import neton.core.http.NotFoundException
import neton.database.api.DbContext
import neton.database.dbContext
import neton.database.dsl.eq

class UserLogic(private val db: DbContext = dbContext()) : DbContext by db {

    suspend fun all(): List<User> =
        UserTable.query { where { User::status eq 1 } }.list()

    suspend fun get(id: Long): User? =
        UserTable.get(id)

    suspend fun create(user: User): User =
        UserTable.insert(user)

    suspend fun update(id: Long, user: User): User {
        val current = UserTable.get(id) ?: throw NotFoundException("User $id not found")
        val updated = current.copy(name = user.name, email = user.email, status = user.status, age = user.age)
        UserTable.update(updated)
        return updated
    }

    suspend fun delete(id: Long) {
        UserTable.destroy(id)
    }

    suspend fun getWithRoles(userId: Long): UserWithRoles? {
        val sql = """
            SELECT u.id, u.name, u.email, u.status, u.age,
                   r.id AS role_id, r.name AS role_name
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
