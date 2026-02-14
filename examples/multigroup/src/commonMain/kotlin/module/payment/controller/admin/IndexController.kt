package module.payment.controller.admin

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.RequireAuth

/**
 * Payment Admin 控制器 - routeGroup=admin, mount=/admin
 * 最终路径: /admin/payment/index
 */
@Controller("/payment/index")
@RequireAuth
class PaymentAdminIndexController {

    @Get("")
    suspend fun index(): String = "payment admin ok"

    @Get("/orders")
    suspend fun orders(): String = "payment admin orders"
}
