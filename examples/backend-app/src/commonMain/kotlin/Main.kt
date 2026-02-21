import config.JWT_SECRET
import model.*
import neton.core.Neton
import neton.core.component.NetonContext
import neton.core.generated.GeneratedNetonConfigRegistry
import neton.database.database
import neton.http.http
import neton.logging.LoggerFactory
import neton.routing.routing
import neton.security.security
import neton.security.jwt.JwtAuthenticatorV1
import logic.AuthLogic
import logic.UserLogic

fun main(args: Array<String>) {
    Neton.run(args) {
        configRegistry(GeneratedNetonConfigRegistry)

        http {
            port = 8080
        }

        database {
            tableRegistry = { clazz ->
                @Suppress("UNCHECKED_CAST")
                when (clazz) {
                    SystemUser::class -> SystemUserTable
                    Role::class -> RoleTable
                    UserRole::class -> UserRoleTable
                    else -> null
                }
            }
        }

        security { }

        routing { }

        onStart {
            val ctx = get(NetonContext::class)
            val loggerFactory = get(LoggerFactory::class)

            // ===== NetonSQL v2 架构：Logic 层 =====
            // 直接使用 Table + DbContext，不需要 Store 层

            // bind AuthLogic
            val jwt = JwtAuthenticatorV1(JWT_SECRET)
            val authLogic = AuthLogic(loggerFactory.get("logic.auth"), jwt)
            ctx.bind(AuthLogic::class, authLogic)

            // bind UserLogic
            val userLogic = UserLogic(loggerFactory.get("logic.user"))
            ctx.bind(UserLogic::class, userLogic)

            // ensure tables + seed data
            SystemUserTable.ensureTable()
            RoleTable.ensureTable()
            UserRoleTable.ensureTable()

            seedData(loggerFactory)
        }
    }
}

/**
 * 初始化种子数据（NetonSQL v2 示例）
 */
private suspend fun seedData(loggerFactory: LoggerFactory) {
    val log = loggerFactory.get("app.seed")
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

    // 1. 创建角色
    if (RoleTable.get(1L) == null) {
        RoleTable.insert(Role(null, "admin", "管理员", "系统管理员，拥有所有权限", now, now))
        RoleTable.insert(Role(null, "editor", "编辑", "可以编辑内容", now, now))
        RoleTable.insert(Role(null, "viewer", "查看者", "只能查看内容", now, now))
        log.info("seed.roles.created", mapOf("count" to 3))
    }

    // 2. 创建管理员用户
    if (SystemUserTable.get(1L) == null) {
        SystemUserTable.insert(
            SystemUser(
                id = null,
                username = "admin",
                passwordHash = "admin123",  // 生产环境应使用 bcrypt
                nickname = "Administrator",
                status = 0,
                deleted = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        log.info("seed.admin.created", mapOf("username" to "admin"))
    }

    // 3. 为管理员分配角色（展示 NetonSQL v2 的 JOIN 查询场景）
    val adminUserRoles = UserRoleTable.query {
        where { UserRole::userId eq 1L }
    }.list()

    if (adminUserRoles.isEmpty()) {
        UserRoleTable.insert(UserRole(null, userId = 1L, roleId = 1L, createdAt = now))  // admin role
        log.info("seed.userRole.created", mapOf("userId" to 1L, "roleId" to 1L))
    }
}
