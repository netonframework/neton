package controller

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post

/**
 * 首页控制器 - 根路径演示
 * 基础路径: /
 * 完整路由: /
 */
@Controller("/")
class HomeController {
    
    /**
     * 首页访问
     * 路由: GET /
     */
    @Get("/")
    fun index(): String {
        return "🏠 欢迎来到 Neton 框架首页！"
    }
} 