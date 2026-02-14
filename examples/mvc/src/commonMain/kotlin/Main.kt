import model.Role
import model.RoleTable
import model.User
import model.UserRole
import model.UserRoleTable
import model.UserTable
import neton.core.Neton
import neton.http.http
import neton.database.database
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {
        http {
            port = 8081
        }
        database {
            tableRegistry = { clazz ->
                @Suppress("UNCHECKED_CAST")
                when (clazz) {
                    User::class -> UserTable
                    Role::class -> RoleTable
                    UserRole::class -> UserRoleTable
                    else -> null
                }
            }
        }
        routing { }
        onStart {
            UserTable.ensureTable()
            RoleTable.ensureTable()
            UserRoleTable.ensureTable()
        }
    }
}
