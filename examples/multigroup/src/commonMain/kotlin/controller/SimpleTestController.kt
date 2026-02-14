package controller

import neton.core.annotations.*

@Controller("/test")
class SimpleTestController {
    
    @Get("/hello")
    fun hello(): String {
        return "Hello from SimpleTestController!"
    }
    
    @Get("/world/{name}")
    fun world(@PathVariable("name") name: String): String {
        return "Hello, $name!"
    }
    
    @Post("/echo")
    fun echo(@QueryParam("message") message: String): String {
        return "Echo: $message"
    }
} 