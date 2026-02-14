package controller

import neton.core.annotations.*

@Controller
class ModernController {
    
    fun index(): String {
        return "Hello from ModernController! KSP 自动扫描成功!"
    }
    
    @Get("/test")
    fun test(): String {
        return "ModernController test endpoint working!"
    }
} 