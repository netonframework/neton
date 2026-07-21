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

        engine.registerRoute(route("controller.app.level.MemberLevelController"))
        engine.registerRoute(route("controller.admin.level.MemberLevelController"))

        assertEquals(2, engine.getRoutes().size)
    }

    @Test
    fun deduplicatesIdenticalPathsWithinTheSameControllerGroup() {
        val engine = DefaultRequestEngine()

        engine.registerRoute(route("controller.admin.level.MemberLevelController"))
        engine.registerRoute(route("controller.admin.level.OtherLevelController"))

        assertEquals(1, engine.getRoutes().size)
    }

    private fun route(controllerClass: String) = RouteDefinition(
        pattern = "/member/level/list",
        method = HttpMethod.GET,
        handler = object : RouteHandler {
            override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? = null
        },
        controllerClass = controllerClass,
        methodName = "list",
    )
}
