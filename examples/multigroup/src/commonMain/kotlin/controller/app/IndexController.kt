package controller.app

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post

/**
 * App 模块首页 - routeGroup=app, mount=/app
 * 最终路径: /app/index, /app/index/user
 */
@Controller("/index")
class AppIndexController {

    @Get("")
    suspend fun index(): String = "app ok"

    @Get("/user")
    suspend fun user(): String = "app user"

    @Post("/submit")
    suspend fun submit(): String = "app submit ok"
}
