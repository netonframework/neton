package logic

import dto.PageResponse
import dto.RoleVO
import dto.UserVO
import dto.UserWithRolesVO
import model.*
import table.*
import neton.database.api.DbContext
import neton.database.dbContext
import neton.database.dsl.*
import neton.logging.Logger

/**
 * 用户业务逻辑（NetonSQL v1 架构最佳实践）
 *
 * 架构层级：Controller → Logic → Table/DbContext → Driver
 * 查询路径：80% DSL + 20% raw SQL escape hatch
 */
class UserLogic(
    private val log: Logger,
    private val db: DbContext = dbContext()
) {

    /**
     * Phase 1：单表分页查询
     *
     * 特性演示：
     * - KProperty1 列引用（SystemUser::username）
     * - 条件构建器（whenNotBlank / whenPresent）
     * - 自动分页（count + select）
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
     * Phase 4：三表 JOIN 查询（获取用户及角色列表）
     *
     * 架构特性：
     * - 显式列选择（typed projection）
     * - 自动 alias（t1, t2, t3）
     * - 强类型 Record8 返回
     * - 手动聚合一对多
     *
     * SQL 等价：
     * SELECT
     *   u.id, u.username, u.nickname, u.status, u.created_at, u.updated_at,
     *   r.id, r.name
     * FROM system_users u
     * LEFT JOIN user_roles ur ON u.id = ur.user_id
     * LEFT JOIN roles r ON ur.role_id = r.id
     * WHERE u.id = ?
     */
    suspend fun getUserWithRoles(userId: Long): UserWithRolesVO? {
        val (q, U) = db.from(SystemUserTable)
        val UR = q.leftJoin(UserRoleTable).on { ur ->
            U.id eq ur.userId
        }
        val R = q.leftJoin(RoleTable).on { r ->
            UR.roleId eq r.id
        }

        // typed projection 返回 Record8<Long?, String, String, Int, Long, Long, Long?, String>
        val records = q.where(U.id eq userId)
            .select(
                U.id,           // v1: Long?
                U.username,     // v2: String
                U.nickname,     // v3: String
                U.status,       // v4: Int
                U.createdAt,    // v5: Long
                U.updatedAt,    // v6: Long
                R.id,           // v7: Long? (LEFT JOIN 可能为 null)
                R.name          // v8: String
            )
            .fetch()

        if (records.isEmpty()) return null

        // 手动聚合：第一行 → User，所有行 → Roles（去重）
        val firstRow = records.first()
        val user = UserVO(
            id = firstRow.v1!!,
            username = firstRow.v2,
            nickname = firstRow.v3,
            status = firstRow.v4,
            createdAt = firstRow.v5,
            updatedAt = firstRow.v6
        )

        // 过滤 NULL JOIN 结果：v7 (Role.id) 为 null 表示无角色
        val roles = records.mapNotNull { row ->
            val roleId = row.v7
            if (roleId != null) {
                RoleVO(
                    id = roleId,
                    code = "",
                    name = row.v8,
                    description = null
                )
            } else null
        }.distinctBy { it.id }

        log.info("user.getUserWithRoles", mapOf("userId" to userId, "roleCount" to roles.size))

        return UserWithRolesVO(
            id = user.id,
            username = user.username,
            nickname = user.nickname,
            status = user.status,
            roles = roles,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }

    /**
     * Phase 4：按角色筛选用户（typed projection）
     *
     * 架构特性：
     * - INNER JOIN（只返回有该角色的用户）
     * - 6 列投影 → Record6
     * - 强类型访问（v1, v2, ..., v6）
     *
     * SQL 等价：
     * SELECT u.id, u.username, u.nickname, u.status, u.created_at, u.updated_at
     * FROM system_users u
     * INNER JOIN user_roles ur ON u.id = ur.user_id
     * INNER JOIN roles r ON ur.role_id = r.id
     * WHERE r.code = ?
     */
    suspend fun listUsersByRole(roleCode: String): List<UserVO> {
        val (q, U) = db.from(SystemUserTable)
        val UR = q.innerJoin(UserRoleTable).on { ur ->
            U.id eq ur.userId
        }
        val R = q.innerJoin(RoleTable).on { r ->
            UR.roleId eq r.id
        }

        // 强类型投影（6 列 → Record6<Long?, String, String, Int, Long, Long>）
        val records = q.where(R.code eq roleCode)
            .select(
                U.id,
                U.username,
                U.nickname,
                U.status,
                U.createdAt,
                U.updatedAt
            )
            .fetch()

        // Record6 强类型访问
        val items = records.map { record ->
            UserVO(
                id = record.v1!!,
                username = record.v2,
                nickname = record.v3,
                status = record.v4,
                createdAt = record.v5,
                updatedAt = record.v6
            )
        }

        log.info("user.listUsersByRole", mapOf("roleCode" to roleCode, "count" to items.size))

        return items
    }
}
