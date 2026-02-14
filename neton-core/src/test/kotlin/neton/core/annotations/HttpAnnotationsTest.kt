package neton.core.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 简化HTTP注解测试
 */
class HttpAnnotationsTest {
    
    @Test
    fun testGetAnnotation() {
        val annotation = TestController::testGet.annotations
            .filterIsInstance<Get>()
            .firstOrNull()
        
        assertNotNull(annotation)
        assertEquals("/test", annotation.path)
    }
    
    @Test
    fun testPostAnnotation() {
        val annotation = TestController::testPost.annotations
            .filterIsInstance<Post>()
            .firstOrNull()
        
        assertNotNull(annotation)
        assertEquals("/create", annotation.path)
    }
    
    @Test
    fun testDefaultPath() {
        val annotation = TestController::defaultPath.annotations
            .filterIsInstance<Get>()
            .firstOrNull()
        
        assertNotNull(annotation)
        assertEquals("", annotation.path) // 空路径，应使用方法名
    }
}

/**
 * 测试控制器
 */
@Controller
class TestController {
    
    @Get("/test")
    fun testGet(): String = "GET response"
    
    @Post("/create")
    fun testPost(): String = "POST response"
    
    @Get // 无路径，使用默认
    fun defaultPath(): String = "Default path"
    
    @Put("/update")
    fun testPut(): String = "PUT response"
    
    @Delete("/delete")
    fun testDelete(): String = "DELETE response"
} 