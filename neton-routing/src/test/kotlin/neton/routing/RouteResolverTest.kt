package neton.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 路由解析器测试
 * 验证4种项目模式的路由解析逻辑
 */
class RouteResolverTest {
    
    /**
     * 测试简单模式路由解析
     */
    @Test
    fun testSimpleMode() {
        val routeGroups = mapOf(
            "default" to RouteGroup("default", RouteBindingType.PATH, "/")
        )
        val resolver = RouteResolver(ProjectMode.SIMPLE, routeGroups)
        
        // 测试正确的路径
        val resolved = resolver.resolveRoute("/user/profile")
        assertNotNull(resolved)
        assertEquals(ProjectMode.SIMPLE, resolved.mode)
        assertEquals(null, resolved.routeGroup)
        assertEquals(null, resolved.module)
        assertEquals("user", resolved.controller)
        assertEquals("profile", resolved.method)
        
        // 测试错误的路径
        assertNull(resolver.resolveRoute("/user"))         // 缺少方法
        assertNull(resolver.resolveRoute("/user/a/b"))     // 路径太长
    }
    
    /**
     * 测试模块模式路由解析
     */
    @Test
    fun testModularMode() {
        val routeGroups = mapOf(
            "default" to RouteGroup("default", RouteBindingType.PATH, "/")
        )
        val resolver = RouteResolver(ProjectMode.MODULAR, routeGroups)
        
        // 测试正确的路径
        val resolved = resolver.resolveRoute("/user/profile/info")
        assertNotNull(resolved)
        assertEquals(ProjectMode.MODULAR, resolved.mode)
        assertEquals(null, resolved.routeGroup)
        assertEquals("user", resolved.module)
        assertEquals("profile", resolved.controller)
        assertEquals("info", resolved.method)
        
        // 测试错误的路径
        assertNull(resolver.resolveRoute("/user/profile"))      // 路径太短
        assertNull(resolver.resolveRoute("/user/a/b/c"))       // 路径太长
    }
    
    /**
     * 测试路由组模式路由解析
     */
    @Test
    fun testRouteGroupMode() {
        val routeGroups = mapOf(
            "default" to RouteGroup("default", RouteBindingType.PATH, "/"),
            "admin" to RouteGroup("admin", RouteBindingType.PATH, "/admin")
        )
        val resolver = RouteResolver(ProjectMode.ROUTE_GROUP, routeGroups)
        
        // 测试默认组
        val defaultResolved = resolver.resolveRoute("/user/list")
        assertNotNull(defaultResolved)
        assertEquals(ProjectMode.ROUTE_GROUP, defaultResolved.mode)
        assertEquals("default", defaultResolved.routeGroup)
        assertEquals(null, defaultResolved.module)
        assertEquals("user", defaultResolved.controller)
        assertEquals("list", defaultResolved.method)
        
        // 测试 admin 组
        val adminResolved = resolver.resolveRoute("/admin/user/manage")
        assertNotNull(adminResolved)
        assertEquals(ProjectMode.ROUTE_GROUP, adminResolved.mode)
        assertEquals("admin", adminResolved.routeGroup)
        assertEquals(null, adminResolved.module)
        assertEquals("user", adminResolved.controller)
        assertEquals("manage", adminResolved.method)
    }
    
    /**
     * 测试完整模式路由解析
     */
    @Test
    fun testFullMode() {
        val routeGroups = mapOf(
            "default" to RouteGroup("default", RouteBindingType.PATH, "/"),
            "admin" to RouteGroup("admin", RouteBindingType.PATH, "/admin")
        )
        val resolver = RouteResolver(ProjectMode.FULL, routeGroups)
        
        // 测试默认组
        val defaultResolved = resolver.resolveRoute("/user/profile/info")
        assertNotNull(defaultResolved)
        assertEquals(ProjectMode.FULL, defaultResolved.mode)
        assertEquals("default", defaultResolved.routeGroup)
        assertEquals("user", defaultResolved.module)
        assertEquals("profile", defaultResolved.controller)
        assertEquals("info", defaultResolved.method)
        
        // 测试 admin 组
        val adminResolved = resolver.resolveRoute("/admin/user/manage/list")
        assertNotNull(adminResolved)
        assertEquals(ProjectMode.FULL, adminResolved.mode)
        assertEquals("admin", adminResolved.routeGroup)
        assertEquals("user", adminResolved.module)
        assertEquals("manage", adminResolved.controller)
        assertEquals("list", adminResolved.method)
    }
    
    /**
     * 测试路由验证
     */
    @Test
    fun testRouteValidation() {
        val resolver = RouteResolver(ProjectMode.SIMPLE, emptyMap())
        
        // 测试有效路由
        val validRoute = ResolvedRoute(
            routeGroup = null,
            module = null,
            controller = "user",
            method = "profile",
            mode = ProjectMode.SIMPLE
        )
        assertEquals(true, resolver.validateRoute(validRoute))
        
        // 测试无效路由（空控制器名）
        val invalidRoute = ResolvedRoute(
            routeGroup = null,
            module = null,
            controller = "",
            method = "profile",
            mode = ProjectMode.SIMPLE
        )
        assertEquals(false, resolver.validateRoute(invalidRoute))
    }
} 