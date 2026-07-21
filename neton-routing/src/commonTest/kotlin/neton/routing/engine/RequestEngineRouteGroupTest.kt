package neton.routing.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteHandler

class RequestEngineRouteGroupTest {
    @Test
    fun keepsIdenticalPathsFromDifferentControllerGroups() {
        val engine = DefaultRequestEngine()

        engine.registerRoute(route("controller.app.level.MemberLevelController", group = "app"))
        engine.registerRoute(route("controller.admin.level.MemberLevelController", group = "admin"))

        assertEquals(2, engine.getRoutes().size)
    }

    @Test
    fun deduplicatesIdenticalPathsWithinTheSameControllerGroup() {
        val engine = DefaultRequestEngine()

        engine.registerRoute(route("controller.admin.level.MemberLevelController", group = "admin"))
        engine.registerRoute(route("controller.admin.level.OtherLevelController", group = "admin"))

        assertEquals(1, engine.getRoutes().size)
    }

    // routeGroup 由 KSP 编译期按目录约定写入，测试直接模拟 KSP 产物
    private fun route(controllerClass: String, group: String?) = RouteDefinition(
        pattern = "/member/level/list",
        method = HttpMethod.GET,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? = null
        },
        controllerClass = controllerClass,
        methodName = "list",
        routeGroup = group,
    )
}
