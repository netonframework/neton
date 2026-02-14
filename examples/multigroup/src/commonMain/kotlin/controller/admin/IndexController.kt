package controller.admin

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.RequireAuth

/**
 * Admin 模块首页 - routeGroup=admin, mount=/admin
 * 最终路径: /admin/index, /admin/index/public
 */
@Controller("/index")
@RequireAuth
class AdminIndexController {

    @Get("")
    suspend fun index(): String = "admin ok"

    @Get("/public")
    @AllowAnonymous
    suspend fun public(): String = "admin public (allow anonymous)"

    @Get("/dashboard")
    suspend fun dashboard(): String = "admin dashboard"
}
