import config.JWT_SECRET
import model.SystemUser
import model.SystemUserTable
import neton.core.Neton
import neton.core.component.NetonContext
import neton.core.generated.GeneratedNetonConfigRegistry
import neton.database.database
import neton.http.http
import neton.logging.LoggerFactory
import neton.routing.routing
import neton.security.security
import neton.security.jwt.JwtAuthenticatorV1
import service.AuthService
import service.UserService

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
                    else -> null
                }
            }
        }

        security { }

        routing { }

        onStart {
            val ctx = get(NetonContext::class)
            val loggerFactory = get(LoggerFactory::class)

            // bind AuthService
            val jwt = JwtAuthenticatorV1(JWT_SECRET)
            val authService = AuthService(loggerFactory.get("service.auth"), jwt)
            ctx.bind(AuthService::class, authService)

            // bind UserService
            val userService = UserService(loggerFactory.get("service.user"))
            ctx.bind(UserService::class, userService)

            // ensure table + seed admin user
            SystemUserTable.ensureTable()
            seedAdminUser(loggerFactory)
        }
    }
}

private suspend fun seedAdminUser(loggerFactory: LoggerFactory) {
    val log = loggerFactory.get("app.seed")
    val existing = SystemUserTable.get(1L)
    if (existing == null) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        SystemUserTable.insert(
            SystemUser(
                id = null,
                username = "admin",
                passwordHash = "admin123",
                nickname = "Administrator",
                status = 0,
                deleted = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        log.info("seed.admin.created", mapOf("username" to "admin"))
    }
}
