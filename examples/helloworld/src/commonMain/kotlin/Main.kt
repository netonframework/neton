import neton.core.Neton
import neton.http.http
import neton.routing.*

fun main(args: Array<String>) {
    Neton.run(args) {

        http {
            port = 8080
        }

        routing {
            get("/") {
                "Hello Neton!"
            }
        }
    }
}
