package neton.routing

/**
 * 控制器注册接口
 */
interface ControllerRegistrar {
    fun registerAll()
}

/**
 * 控制器扫描器 - 使用 KSP 生成的注册代码
 * 
 * 在编译时，KSP 会扫描所有 @Controller 注解的类，
 * 并生成 ControllerRegistry 来注册所有控制器。
 */
object ControllerScanner {
    
    private var registrar: ControllerRegistrar? = null
    
    /**
     * 设置控制器注册器（由生成的代码调用）
     */
    fun setRegistrar(registrar: ControllerRegistrar) {
        this.registrar = registrar
    }
    
    /**
     * 注册所有控制器
     * 直接使用 KSP 生成的注册代码，无需指定路径
     */
    fun registerAllControllers() {
        val log = RoutingLog.log
        log?.info("routing.scan.ksp")
        val currentRegistrar = registrar
        if (currentRegistrar != null) {
            try {
                currentRegistrar.registerAll()
                log?.info("routing.scan.registrar.ok")
                return
            } catch (e: Exception) {
                log?.warn("routing.scan.registrar.failed", mapOf("message" to (e.message ?: "")))
            }
        }
        log?.info("routing.scan.no_registrar", mapOf("hint" to "Use GeneratedInitializer.initialize() or register a custom ControllerRegistrar"))
    }
}

// ==================== 数据类定义 ====================

/**
 * 控制器文件信息
 */
data class ControllerFile(
    val absolutePath: String,
    val relativePath: String,
    val type: ControllerType,
    val className: String,
    val groupName: String?,
    val moduleName: String?
)

/**
 * 控制器类型
 */
enum class ControllerType {
    SIMPLE,          // controller/*.kt
    GROUPED,         // controller/*/*.kt
    MODULAR,         // modules/*/controller/*.kt
    MODULAR_GROUPED  // modules/*/controller/*/*.kt
}

/**
 * 控制器元数据
 */
data class ControllerMetadata(
    val className: String,
    val basePath: String,
    val methods: List<MethodMetadata>,
    val controllerFile: ControllerFile
)

/**
 * 方法元数据
 */
data class MethodMetadata(
    val name: String,
    val httpMethod: String,
    val path: String,
    val parameters: List<ParamInfo>
)

/**
 * 参数信息
 */
data class ParamInfo(
    val name: String,
    val type: String,
    val isAuthenticationPrincipal: Boolean = false,
    val authenticationRequired: Boolean = true
)

/**
 * 临时的后备路由处理器 - 模拟真正的控制器调用
 * RESERVED FOR v1.1: 可被真正的控制器调用替代
 */
internal class FallbackRouteHandler(
    private val controllerClass: String,
    private val methodName: String
) : neton.routing.engine.RouteHandler {
    
    override suspend fun invoke(context: neton.core.http.HttpContext, args: neton.core.http.HandlerArgs): Any? {
        return when("$controllerClass.$methodName") {
            "SimpleController.hello" -> "Hello from SimpleController!"
            "HomeController.index" -> "🏠 欢迎来到 Neton 框架首页！"
            "ParameterBindingController.pathParam" -> {
                val userId = args.first("userId") ?: context.request.pathParams["userId"] ?: "unknown"
                "👤 路径参数 userId: $userId"
            }
            "ParameterBindingController.queryParam" -> {
                val keyword = args.first("keyword") ?: context.request.queryParams["keyword"] ?: "unknown"
                val page = args.first("page") ?: "1"
                val size = args.first("size") ?: "10"
                "🔍 查询参数 - keyword: '$keyword', page: $page, size: $size"
            }
            "SimpleController.getUser" -> {
                val userId = args.first("id") ?: context.request.pathParams["id"] ?: "unknown"
                "👤 用户信息 - ID: $userId"
            }
            else -> "📤 Response from $controllerClass.$methodName"
        }
    }
} 