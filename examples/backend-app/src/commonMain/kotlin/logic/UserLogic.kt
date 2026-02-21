package logic

import dto.PageResponse
import dto.UserVO
import dto.UserWithRolesVO
import model.*
import neton.database.adapter.sqlx.SqlxDbContext
import neton.database.api.intoOrNull
import neton.database.dsl.*
import neton.logging.Logger

/**
 * 用户业务逻辑（NetonSQL v2 架构：Logic 层直接使用 Table + DbContext）
 *
 * 架构层级：
 * Controller → Logic → Table → DbContext → Driver
 *
 * 不再需要 Store 层。
 */
class UserLogic(private val log: Logger) {

    private val db = SqlxDbContext

    /**
     * Phase 1：单表分页查询（保持兼容）
     */
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

    /**
     * Phase 4：JOIN 查询 - 获取用户及其角色列表
     *
     * 使用 NetonSQL v2 的强类型 JOIN + 手动映射（一对多场景）
     */
    suspend fun getUserWithRoles(userId: Long): UserWithRolesVO? {
        // 构建三表 JOIN 查询
        val (q, U) = db.from(SystemUserTable)
        val UR = q.leftJoin(UserRoleTable).on { U[SystemUser::id] eq it[UserRole::userId] }
        val R = q.leftJoin(RoleTable).on { UR[UserRole::roleId] eq it[Role::id] }

        // WHERE 条件
        q.where(U[SystemUser::id] eq userId)

        // 执行查询（Row 逃生口，适合一对多聚合）
        val rows = q.selectAllRows().fetchRows()

        if (rows.isEmpty()) return null

        // 手动映射：一对多聚合
        val firstRow = rows.first()
        val user = firstRow.into<SystemUser>()

        val roles = rows.mapNotNull { row ->
            // LEFT JOIN 可能为 null，使用 intoOrNull
            row.intoOrNull<Role>("", Role::id)
        }.distinctBy { it.id }

        log.info("user.getUserWithRoles", mapOf("userId" to userId, "rolesCount" to roles.size))

        return UserWithRolesVO(
            id = user.id!!,
            username = user.username,
            nickname = user.nickname,
            status = user.status,
            roles = roles.map { RoleVO(it.id!!, it.code, it.name) },
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }

    /**
     * Phase 4：JOIN 查询 - 按角色筛选用户（typed projection 示例）
     *
     * 使用强类型投影，不手动映射
     */
    suspend fun listUsersByRole(roleCode: String): List<UserVO> {
        val (q, U) = db.from(SystemUserTable)
        val UR = q.innerJoin(UserRoleTable).on { U[SystemUser::id] eq it[UserRole::userId] }
        val R = q.innerJoin(RoleTable).on { UR[UserRole::roleId] eq it[Role::id] }

        q.where(R[Role::code] eq roleCode)

        // 使用 typed projection：强类型返回
        val records = q.select(
            U[SystemUser::id],
            U[SystemUser::username],
            U[SystemUser::nickname],
            U[SystemUser::status],
            U[SystemUser::createdAt],
            U[SystemUser::updatedAt]
        ).fetch()

        log.info("user.listUsersByRole", mapOf("roleCode" to roleCode, "count" to records.size))

        return records.map { (id, username, nickname, status, createdAt, updatedAt) ->
            UserVO(
                id = id!!,
                username = username,
                nickname = nickname,
                status = status,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }

    /**
     * Phase 1：创建用户（单表操作）
     */
    suspend fun createUser(username: String, password: String, nickname: String): Long {
        val user = SystemUser(
            id = null,
            username = username,
            passwordHash = password, // 生产环境应使用 bcrypt
            nickname = nickname,
            status = 0,
            deleted = 0
        )

        val id = SystemUserTable.insert(user)
        log.info("user.create", mapOf("userId" to id, "username" to username))
        return id
    }

    /**
     * 为用户分配角色（展示事务场景 - TODO: 等 DbContext.transaction 实现）
     */
    suspend fun assignRole(userId: Long, roleId: Long) {
        // TODO: 使用 db.transaction { ... } 包裹
        val userRole = UserRole(
            id = null,
            userId = userId,
            roleId = roleId
        )
        UserRoleTable.insert(userRole)
        log.info("user.assignRole", mapOf("userId" to userId, "roleId" to roleId))
    }
}

// ===== DTO =====

@kotlinx.serialization.Serializable
data class RoleVO(
    val id: Long,
    val code: String,
    val name: String
)
