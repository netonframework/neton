package module.payment.controller

import neton.core.annotations.Controller
import neton.core.annotations.Get

/**
 * Payment 模块默认控制器 - routeGroup=default (无 mount)
 * 最终路径: /payment/index
 */
@Controller("/payment/index")
class PaymentIndexController {

    @Get("")
    suspend fun index(): String = "payment module (default group)"

    @Get("/status")
    suspend fun status(): String = "payment status"
}
