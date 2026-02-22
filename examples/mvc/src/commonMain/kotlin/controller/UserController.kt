package controller

import logic.UserLogic
import model.User
import model.UserWithRoles
import neton.core.annotations.*
import neton.core.http.*
import neton.logging.Logger
import neton.logging.Log

@Controller("/api/users")
@Log
class UserController(
    private val log: Logger,
    private val userLogic: UserLogic = UserLogic()
) {

    @Get
    suspend fun all(): List<User> = userLogic.all()

    @Get("/{id}")
    suspend fun get(id: Long): User? {
        log.info("user.get", mapOf("userId" to id))
        return userLogic.get(id)
    }

    @Get("/{id}/with-roles")
    suspend fun getWithRoles(id: Long): UserWithRoles? =
        userLogic.getWithRoles(id)

    @Post
    suspend fun create(@Body user: User): User = userLogic.create(user)

    @Put("/{id}")
    suspend fun update(id: Long, @Body user: User): User = userLogic.update(id, user)

    @Delete("/{id}")
    suspend fun delete(id: Long) = userLogic.delete(id)
}
