package service

import dto.PageResponse
import dto.UserVO
import model.SystemUser
import model.SystemUserTable
import neton.database.dsl.eq
import neton.database.dsl.like
import neton.logging.Logger

class UserService(private val log: Logger) {

    suspend fun page(
        page: Int,
        size: Int,
        username: String?,
        status: Int?
    ): PageResponse<UserVO> {
        val query = SystemUserTable.query {
            where {
                and(
                    whenNotBlank(username) { SystemUser::username like "%$it%" },
                    whenPresent(status) { SystemUser::status eq it }
                )
            }
        }

        val result = query.page(page, size)

        val items = result.items.map { user ->
            UserVO(
                id = user.id!!,
                username = user.username,
                nickname = user.nickname,
                status = user.status,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt
            )
        }

        log.info("user.page", mapOf("page" to page, "size" to size, "total" to result.total))

        return PageResponse(
            items = items,
            total = result.total,
            page = result.page,
            size = result.size,
            totalPages = result.totalPages
        )
    }
}
