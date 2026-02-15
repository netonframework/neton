package controller

import model.User
import model.UserTable
import neton.database.dsl.ColumnRef
import model.UserWithRoles
import neton.core.annotations.*
import neton.core.http.*
import store.UserStore
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/users")
@Log
class UserController(
    private val log: Logger,
    private val userStore: UserStore = UserStore()
) {

    @Get
    suspend fun all(): List<User> =
        UserTable.query { where { ColumnRef("status") eq 1 } }.list()

    @Get("/{id}")
    suspend fun get(id: Long): User? {
        log.info("user.get", mapOf("userId" to id))
        return UserTable.get(id)
    }

    /** 聚合查询：用户 + 角色列表 */
    @Get("/{id}/with-roles")
    suspend fun getWithRoles(id: Long): UserWithRoles? =
        userStore.getWithRoles(id)

    @Post
    suspend fun create(@Body user: User): User =
        UserTable.save(user)

    @Put("/{id}")
    suspend fun update(id: Long, @Body user: User): User {
        val current = UserTable.get(id) ?: throw NotFoundException("User $id not found")
        val updated = current.copy(name = user.name, email = user.email, status = user.status, age = user.age)
        UserTable.update(updated)
        return updated
    }

    @Delete("/{id}")
    suspend fun delete(id: Long) {
        UserTable.destroy(id)
    }
}
