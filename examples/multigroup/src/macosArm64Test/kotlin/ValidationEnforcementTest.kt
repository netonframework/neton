import controller.ValidationDemoController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import neton.core.component.NetonContext
import neton.core.generated.GeneratedInitializer
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.RequestEngine
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.core.interfaces.RouteDefinition
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 端到端（KSP 生成路由 → 标准 dispatcher → 信封）：约束不满足的请求体返回
 * 400 `VALIDATION_FAILED`，字段码来自约束，handler 一次都不执行。
 *
 * 回归：校验器曾经经由 `bindIfAbsent(ValidatorRegistry)` 注册，多模块宿主里只有第一个装配的
 * 模块的注册表生效，其余模块的 DTO 查不到校验器就被静默放行（空密码改密成功）。
 */
@OptIn(ExperimentalAtomicApi::class)
class ValidationEnforcementTest {

    private val dispatcher by lazy {
        val ctx = NetonContext(emptyArray())
        ctx.bind(RequestEngine::class, CollectingRequestEngine())
        GeneratedInitializer.initialize(ctx)
        BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    private fun put(body: String) = runBlocking {
        dispatcher.dispatch(
            BufferedHttpRequest(
                "PUT", "/validation/password", "",
                mapOf("Content-Type" to listOf("application/json")),
                body.encodeToByteArray(),
            )
        )
    }

    private fun errorsOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun invalidBodiesAreRejectedBeforeTheHandlerRuns() {
        val before = ValidationDemoController.invocations.load()
        for ((body, field, code) in listOf(
            Triple("""{"password":""}""", "password", "REQUIRED"),
            Triple("""{"password":"abc"}""", "password", "TOO_SHORT"),
            Triple("""{"password":"long-enough","nickname":"x"}""", "nickname", "TOO_SHORT"),
        )) {
            val response = put(body)
            val raw = response.body.decodeToString()
            assertEquals(400, response.status, raw)
            val envelope = errorsOf(raw)
            assertEquals(10100, envelope["code"]!!.jsonPrimitive.int, raw)
            assertEquals("VALIDATION_FAILED", envelope["message"]!!.jsonPrimitive.content, raw)
            val error = envelope["errors"]!!.jsonArray.map { it.jsonObject }.first { it["field"]!!.jsonPrimitive.content == field }
            assertEquals(code, error["code"]!!.jsonPrimitive.content, raw)
            if (code == "TOO_SHORT") {
                val min = error["params"]!!.jsonObject["min"]!!.jsonPrimitive.long
                assertEquals(if (field == "password") 8L else 2L, min, raw)
            }
        }
        assertEquals(before, ValidationDemoController.invocations.load(), "handler must not run for an invalid body")
    }

    @Test
    fun validBodyReachesTheHandler() {
        val before = ValidationDemoController.invocations.load()
        val response = put("""{"password":"long-enough"}""")
        assertEquals(200, response.status, response.body.decodeToString())
        assertEquals(before + 1, ValidationDemoController.invocations.load())
    }
}

/** 只收集生成代码注册的路由，交给标准 dispatcher 匹配 */
private class CollectingRequestEngine : RequestEngine {
    private val routes = mutableListOf<RouteDefinition>()
    override fun registerRoute(route: RouteDefinition) { routes += route }
    override fun getRoutes(): List<RouteDefinition> = routes
}
