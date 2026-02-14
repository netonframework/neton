package controller.admin

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.AllowAnonymous

/**
 * Admin 根路径 - routeGroup=admin, mount=/admin
 * 最终路径: /admin, /admin/
 */
@Controller("")
@AllowAnonymous
class AdminHomeController {

    @Get("")
    suspend fun index(): String = "admin home"
}
