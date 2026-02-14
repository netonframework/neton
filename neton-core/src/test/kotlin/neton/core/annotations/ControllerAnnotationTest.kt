package neton.core.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 控制器注解测试
 * 验证 @Controller 和 HTTP 方法注解的路径参数功能
 */
class ControllerAnnotationTest {
    
    /**
     * 测试控制器 - 带基础路径
     */
    @Controller("/api/users")
    class UserApiController {
        
        @Get("/")
        fun list(): String = "用户列表"
        
        @Get("/{id}")
        fun getUser(): String = "获取用户"
        
        @Post("/")
        fun create(): String = "创建用户"
        
        @Put("/{id}")
        fun update(): String = "更新用户"
        
        @Delete("/{id}")
        fun delete(): String = "删除用户"
    }
    
    /**
     * 测试控制器 - 根路径
     */
    @Controller("/")
    class RootController {
        
        @Get("/")
        fun index(): String = "首页"
        
        @Get("/about")
        fun about(): String = "关于页面"
    }
    
    /**
     * 测试控制器 - 无基础路径
     */
    @Controller
    class SimpleController {
        
        @Get("/simple")
        fun simple(): String = "简单路由"
        
        @Post("/data")
        fun postData(): String = "提交数据"
    }
    
    @Test
    fun testControllerAnnotationWithPath() {
        // 验证 @Controller 注解可以带路径参数
        val controllerAnnotation = UserApiController::class.annotations
            .filterIsInstance<Controller>()
            .firstOrNull()
        
        assertTrue(controllerAnnotation != null, "应该有 @Controller 注解")
        assertEquals("/api/users", controllerAnnotation.path, "控制器路径应该是 /api/users")
    }
    
    @Test
    fun testHttpMethodAnnotationsWithPath() {
        // 验证 HTTP 方法注解可以带路径参数
        val methods = UserApiController::class.java.declaredMethods
        
        val listMethod = methods.find { it.name == "list" }
        val getAnnotation = listMethod?.getAnnotation(Get::class.java)
        assertEquals("/", getAnnotation?.path, "list 方法的路径应该是 /")
        
        val getUserMethod = methods.find { it.name == "getUser" }
        val getUserAnnotation = getUserMethod?.getAnnotation(Get::class.java)
        assertEquals("/{id}", getUserAnnotation?.path, "getUser 方法的路径应该是 /{id}")
        
        val createMethod = methods.find { it.name == "create" }
        val postAnnotation = createMethod?.getAnnotation(Post::class.java)
        assertEquals("/", postAnnotation?.path, "create 方法的路径应该是 /")
    }
    
    @Test
    fun testRootControllerPath() {
        // 验证根路径控制器
        val controllerAnnotation = RootController::class.annotations
            .filterIsInstance<Controller>()
            .firstOrNull()
        
        assertEquals("/", controllerAnnotation?.path, "根控制器路径应该是 /")
    }
    
    @Test
    fun testControllerWithoutPath() {
        // 验证无路径参数的控制器
        val controllerAnnotation = SimpleController::class.annotations
            .filterIsInstance<Controller>()
            .firstOrNull()
        
        assertEquals("", controllerAnnotation?.path, "无路径控制器的路径应该是空字符串")
    }
    
    @Test
    fun testAllHttpMethodAnnotations() {
        // 验证所有 HTTP 方法注解都支持路径参数
        @Controller("/test")
        class TestController {
            @Get("/get") fun testGet(): String = "GET"
            @Post("/post") fun testPost(): String = "POST"
            @Put("/put") fun testPut(): String = "PUT"
            @Delete("/delete") fun testDelete(): String = "DELETE"
            @Patch("/patch") fun testPatch(): String = "PATCH"
            @Options("/options") fun testOptions(): String = "OPTIONS"
            @Head("/head") fun testHead(): String = "HEAD"
        }
        
        val methods = TestController::class.java.declaredMethods
        
        // 验证每个方法的注解路径
        assertEquals("/get", methods.find { it.name == "testGet" }?.getAnnotation(Get::class.java)?.path)
        assertEquals("/post", methods.find { it.name == "testPost" }?.getAnnotation(Post::class.java)?.path)
        assertEquals("/put", methods.find { it.name == "testPut" }?.getAnnotation(Put::class.java)?.path)
        assertEquals("/delete", methods.find { it.name == "testDelete" }?.getAnnotation(Delete::class.java)?.path)
        assertEquals("/patch", methods.find { it.name == "testPatch" }?.getAnnotation(Patch::class.java)?.path)
        assertEquals("/options", methods.find { it.name == "testOptions" }?.getAnnotation(Options::class.java)?.path)
        assertEquals("/head", methods.find { it.name == "testHead" }?.getAnnotation(Head::class.java)?.path)
    }
} 