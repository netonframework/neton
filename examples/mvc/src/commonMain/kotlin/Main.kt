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
        database { }
        routing { }
        // KSP 为本应用的 @Controller 生成的注册器，必须显式传入
        modules(neton.core.generated.GeneratedInitializer)
        onStart {
            UserTable.ensureTable()
            RoleTable.ensureTable()
            UserRoleTable.ensureTable()
        }
    }
}
