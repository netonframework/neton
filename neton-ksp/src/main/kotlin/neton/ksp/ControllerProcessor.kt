package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

class ControllerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    private val moduleId: String? = options["neton.moduleId"]?.takeIf { it.isNotBlank() }

    // 注解的完全限定名称 - 不再直接依赖类
    private val controllerAnnotationName = "neton.core.annotations.Controller"
    private val lockAnnotationName = "neton.redis.lock.Lock"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 使用字符串名称查找注解，不依赖具体类
        val symbols = resolver.getSymbolsWithAnnotation(controllerAnnotationName)
        val controllers = symbols.filterIsInstance<KSClassDeclaration>().toList()

        if (controllers.isNotEmpty()) {
            logger.info("Found ${controllers.size} controllers to process")
            generateInitializer(controllers)
        } else {
            logger.info("No controllers found with @Controller annotation")
        }

        return emptyList()
    }

    private fun generateInitializer(controllers: List<KSClassDeclaration>) {
        // 模块模式：生成 internal 路由帮助类 + 写 sink 片段
        // 兼容模式：生成全局 GeneratedInitializer
        val generatedPkg = if (moduleId != null) "neton.module.$moduleId.generated" else "neton.core.generated"
        val generatedClassName =
            if (moduleId != null) "${moduleId.toPascalCase()}RouteInitializer" else "GeneratedInitializer"

        if (moduleId != null) {
            // 写 sink 片段：ModuleInitializer 委托调用
            ModuleFragmentSink.addStat(moduleId, "routes", getTotalRouteCount(controllers))
            ModuleFragmentSink.addImport(moduleId, "import $generatedPkg.$generatedClassName")
            ModuleFragmentSink.addFragment(
                moduleId,
                "routes",
                "注册路由（${controllers.size} 个控制器）",
                "        $generatedClassName.initialize(ctx)"
            )
        }

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true, *controllers.mapNotNull { it.containingFile }.toTypedArray()),
            packageName = generatedPkg,
            fileName = generatedClassName
        )

        OutputStreamWriter(file).use { writer ->
            writer.write(
                """
                package $generatedPkg

                import neton.core.interfaces.RequestEngine
                import neton.core.interfaces.RouteDefinition
                import neton.core.interfaces.RouteHandler
                import neton.core.http.HttpContext
                import neton.core.http.HandlerArgs
                import neton.core.http.HttpMethod
                import neton.core.http.ParamConverters
                import neton.core.http.UnsupportedMediaTypeException
                import neton.core.interfaces.Identity
                import neton.core.interfaces.SecurityAttributes
                import neton.core.http.UploadFile
                import neton.core.http.UploadFiles

                """.trimIndent()
            )

            // 导入控制器类
            controllers.forEach { controller ->
                writer.write("import ${controller.qualifiedName!!.asString()}\n")
            }
            // 收集 Body 参数类型（@Body 或约定推断的 POST/PUT/PATCH 复杂类型）
            val bodyMethods = setOf("Post", "Put", "Patch")
            val simpleTypes =
                setOf("kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Boolean", "kotlin.Double", "kotlin.Float")
            val contextTypes = setOf(
                "neton.core.http.HttpContext",
                "neton.core.http.Ctx",
                "neton.core.http.HttpRequest",
                "neton.core.http.HttpResponse",
                "neton.core.http.HttpSession",
                "neton.core.interfaces.Identity"
            )
            val bodyParamTypes = controllers.flatMap { c ->
                c.getAllFunctions().flatMap { f ->
                    val isBodyMethod = f.annotations.any { bodyMethods.contains(it.shortName.asString()) }
                    f.parameters.filter { p ->
                        val typeName = p.type.resolve().declaration.qualifiedName!!.asString()
                        p.annotations.any { it.shortName.asString() == "Body" } ||
                                (isBodyMethod && !simpleTypes.contains(typeName) && !contextTypes.contains(typeName))
                    }.map { it.type.resolve().declaration.qualifiedName!!.asString() }
                }
            }.toSet()
            writer.write("import neton.core.http.ValidationException\n")
            writer.write("import neton.core.http.ValidationError\n")
            val anyLock = controllers.any { c ->
                c.getAllFunctions().any { f -> f.annotations.any { it.shortName.asString() == "Lock" } }
            }
            val anyCache = controllers.any { c ->
                c.getAllFunctions().any { f ->
                    f.annotations.any { it.shortName.asString() in listOf("Cacheable", "CachePut", "CacheEvict") }
                }
            }
            val cacheReturnTypes = mutableSetOf<String>()
            if (anyCache) {
                controllers.forEach { c ->
                    c.getAllFunctions().forEach { f ->
                        if (f.annotations.any { it.shortName.asString() in listOf("Cacheable", "CachePut") }) {
                            val rt = f.returnType?.resolve()?.declaration ?: return@forEach
                            val q = (rt as? KSClassDeclaration)?.qualifiedName?.asString()
                            if (q != null && q != "kotlin.Unit" && q != "kotlin.Nothing") cacheReturnTypes.add(q)
                        }
                    }
                }
            }
            if (anyLock) {
                writer.write("import neton.redis.lock.LockManager\n")
                writer.write("import kotlin.time.Duration.Companion.milliseconds\n")
            }
            if (anyCache) {
                writer.write("import neton.cache.CacheManager\n")
                writer.write("import neton.cache.getCache\n")
                writer.write("import neton.cache.CacheKeyHash\n")
                writer.write("import neton.core.http.HttpException\n")
                writer.write("import neton.core.http.HttpStatus\n")
                writer.write("import kotlin.time.Duration.Companion.milliseconds\n")
                cacheReturnTypes.forEach { writer.write("import $it\n") }
            }
            val anyLog = controllers.any { c ->
                c.primaryConstructor?.parameters?.any { p ->
                    val decl = p.type.resolve().declaration
                    (decl as? KSClassDeclaration)?.qualifiedName?.asString() == "neton.logging.Logger" ||
                            p.type.resolve().toString().contains("neton.logging.Logger")
                } == true
            }
            if (anyLog) {
                writer.write("import neton.logging.LoggerFactory\n")
            }
            // 收集 @Serializable 返回类型（用于 JSON 序列化）
            val serializableReturnImports = mutableSetOf<String>()
            controllers.forEach { c ->
                c.getAllFunctions()
                    .filter { f -> f.annotations.any { httpAnnotations.containsKey(it.shortName.asString()) } }
                    .forEach { f ->
                        val rt = f.returnType?.resolve() ?: return@forEach
                        val expr = buildSerializerExpression(rt)
                        if (expr != null) {
                            collectSerializableImports(rt, serializableReturnImports)
                        }
                    }
            }
            val hasSerializableReturn = serializableReturnImports.isNotEmpty()
            val needsJson = bodyParamTypes.isNotEmpty() || hasSerializableReturn
            if (needsJson) {
                writer.write("import kotlinx.serialization.json.Json\n")
            }
            if (bodyParamTypes.isNotEmpty()) {
                writer.write("import neton.validation.ValidatorRegistry\n")
                bodyParamTypes.forEach { writer.write("import $it\n") }
            }
            if (hasSerializableReturn) {
                writer.write("import neton.core.http.JsonContent\n")
                serializableReturnImports.forEach { writer.write("import $it\n") }
            }

            writer.write(
                """

/**
 * KSP 自动生成的路由初始化器
 *
 * 此文件由 Neton KSP 处理器自动生成，请勿手动编辑。
 * 包含 ${controllers.size} 个控制器的路由注册。
 */
${if (moduleId != null) "internal " else ""}object $generatedClassName {
"""
            )

            writer.write(
                """
    /**
     * 初始化所有 KSP 生成的路由（从 ctx 获取 RequestEngine）
     * 签名必须与 neton-core 的 fallback 一致：initialize(ctx: NetonContext?)
     */
    fun initialize(ctx: neton.core.component.NetonContext?) {
        if (ctx == null) return
        val engine = ctx.get(neton.core.interfaces.RequestEngine::class)
        registerRoutes(engine, ctx)
    }

    /**
     * 注册所有路由到请求引擎
     */
    private fun registerRoutes(engine: RequestEngine, ctx: neton.core.component.NetonContext) {
"""
            )

            // 为每个控制器生成路由注册代码
            controllers.forEach { controller ->
                generateControllerRoutes(writer, controller, "ctx")
            }

            writer.write(
                """
    }

    /**
     * 获取总路由数量
     */
    private fun getTotalRoutes(): Int {
        return ${getTotalRouteCount(controllers)}
    }

    /**
     * 获取控制器统计信息
     */
    fun getControllerStats(): String {
        return "Controllers: ${controllers.size}, Routes: ${'$'}{getTotalRoutes()}"
    }
}
"""
            )
        }
    }

    /**
     * 构建 Controller 实例化表达式：按主构造参数顺序解析注入。
     * NetonContext -> ctx；Logger -> ctx.get(LoggerFactory::class).get("完全限定类名")；其余 -> ctx.get(ParamType::class)。
     */
    private fun buildControllerInstantiation(controller: KSClassDeclaration, ctxParam: String): String {
        val controllerName = controller.qualifiedName!!.asString()
        val params = controller.primaryConstructor?.parameters ?: return "$controllerName()"
        if (params.isEmpty()) return "$controllerName()"
        val args = params.map { p ->
            val decl = p.type.resolve().declaration
            val typeName = (decl as? KSClassDeclaration)?.qualifiedName?.asString() ?: p.type.resolve().toString()
            val typeStr = p.type.resolve().toString()
            when {
                typeName == "neton.core.component.NetonContext" || typeStr.contains("NetonContext") -> ctxParam
                typeName == "neton.logging.Logger" || typeStr.contains("neton.logging.Logger") -> "ctx.get(neton.logging.LoggerFactory::class).get(\"$controllerName\")"
                else -> "ctx.get($typeName::class)"
            }
        }
        return "$controllerName(${args.joinToString(", ")})"
    }

    private fun generateControllerRoutes(writer: OutputStreamWriter, controller: KSClassDeclaration, ctxParam: String) {
        val controllerName = controller.qualifiedName!!.asString()
        val controllerClassName = controller.simpleName.asString()
        val controllerInstantiation = buildControllerInstantiation(controller, ctxParam)

        // 获取控制器基础路径
        val controllerBasePath = controller.annotations
            .firstOrNull { it.shortName.asString() == "Controller" }
            ?.arguments?.firstOrNull()?.value as? String ?: ""

        writer.write(
            """
        // === ${controllerClassName} 控制器路由 ===
"""
        )

        controller.getAllFunctions()
            .filter { f -> f.annotations.any { httpAnnotations.containsKey(it.shortName.asString()) } }
            .forEach { function ->
                val httpMethod = function.annotations
                    .firstOrNull { httpAnnotations.containsKey(it.shortName.asString()) }
                    ?.let { httpAnnotations[it.shortName.asString()] } ?: "GET"
                checkBodyAmbiguity(controller, function, httpMethod)
                generateRouteRegistration(writer, controller, function, controllerBasePath, controllerInstantiation)
            }
    }

    private fun generateRouteRegistration(
        writer: OutputStreamWriter,
        controller: KSClassDeclaration,
        function: KSFunctionDeclaration,
        controllerBasePath: String,
        controllerInstantiation: String = "${controller.qualifiedName!!.asString()}()"
    ) {
        val controllerName = controller.qualifiedName!!.asString()
        val methodName = function.simpleName.asString()

        // 获取 HTTP 注解信息
        val httpAnnotation = function.annotations.first { annotation ->
            httpAnnotations.containsKey(annotation.shortName.asString())
        }

        val httpMethod = httpAnnotations[httpAnnotation.shortName.asString()]!!
        val functionPath = httpAnnotation.arguments.firstOrNull()?.value as? String ?: ""
        // 规范化路径：去掉多余尾斜杠，使 GET /api/products 可匹配（列表接口无尾斜杠）
        val base = controllerBasePath.trimEnd('/').ifEmpty { "" }
        val path = functionPath.trimEnd('/')
        val fullPath = when {
            base.isEmpty() -> path.ifEmpty { "/" }
            path.isEmpty() -> base.let { if (it.startsWith("/")) it else "/$it" }
            else -> listOf(base.trimStart('/'), path).joinToString("/").let { if (it.startsWith("/")) it else "/$it" }
        }.replace("//", "/").ifEmpty { "/" }

        val lockAnn = function.annotations.firstOrNull { it.shortName.asString() == "Lock" }
        val lockKeyExpr: String?
        val ttlMs: Long
        val waitMs: Long
        val retryMs: Long
        if (lockAnn != null) {
            val keyArg = lockAnn.arguments.firstOrNull { it.name?.asString() == "key" }?.value as? String ?: "lock"
            lockKeyExpr = resolveLockKeyExpression(keyArg)
            ttlMs = (lockAnn.arguments.firstOrNull { it.name?.asString() == "ttlMs" }?.value as? Long) ?: 10_000L
            waitMs = (lockAnn.arguments.firstOrNull { it.name?.asString() == "waitMs" }?.value as? Long) ?: 0L
            retryMs = (lockAnn.arguments.firstOrNull { it.name?.asString() == "retryMs" }?.value as? Long) ?: 50L
        } else {
            lockKeyExpr = null
            ttlMs = 0L
            waitMs = 0L
            retryMs = 50L
        }

        val allowAnonymous = function.annotations.any { it.shortName.asString() == "AllowAnonymous" } ||
                controller.annotations.any { it.shortName.asString() == "AllowAnonymous" }
        val requireAuth = function.annotations.any { it.shortName.asString() == "RequireAuth" } ||
                controller.annotations.any { it.shortName.asString() == "RequireAuth" }
        // @Permission: 方法级覆盖类级，不允许多个（fail-fast）
        val methodPermissions = function.annotations.filter { it.shortName.asString() == "Permission" }.toList()
        if (methodPermissions.size > 1) {
            logger.error(
                "Neton: Multiple @Permission annotations on ${controller.simpleName.asString()}#${function.simpleName.asString()} is not allowed. Use a single @Permission with a composite key like \"module:action\".",
                function
            )
        }
        val classPermissions = controller.annotations.filter { it.shortName.asString() == "Permission" }.toList()
        if (classPermissions.size > 1) {
            logger.error(
                "Neton: Multiple @Permission annotations on ${controller.simpleName.asString()} is not allowed.",
                controller
            )
        }
        val permissionAnn = methodPermissions.firstOrNull()
            ?: classPermissions.firstOrNull()
        val permission = permissionAnn?.arguments?.firstOrNull()?.value as? String

        val cacheableAnn = function.annotations.firstOrNull { ann ->
            (ann.annotationType.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString() == "neton.cache.Cacheable"
        }
        val cachePutAnn = function.annotations.firstOrNull { ann ->
            (ann.annotationType.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString() == "neton.cache.CachePut"
        }
        val cacheEvictAnn = function.annotations.firstOrNull { ann ->
            (ann.annotationType.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString() == "neton.cache.CacheEvict"
        }

        val innerInvoke = "ctrl.$methodName(${generateMethodCallParameters(function, fullPath, httpMethod)})"
        // 检查返回类型是否为 @Serializable，如果是则在编译期生成 JSON 序列化代码
        val returnType = function.returnType?.resolve()
        val serializerExpr = returnType?.let { buildSerializerExpression(it) }

        /**
         * 将控制器方法调用包装为 JsonContent（如果返回 @Serializable 类型）。
         * 生成: val _r = ctrl.method(...); return JsonContent(Json.encodeToString(Serializer, _r))
         * 这样 Ktor 不需要在运行时通过 guessSerializer() 解析泛型类型。
         */
        fun wrapWithJsonContent(invokeExpr: String): String {
            return if (serializerExpr != null) {
                "val _r = $invokeExpr\n                        return JsonContent(Json.encodeToString($serializerExpr, _r))"
            } else {
                "return $invokeExpr"
            }
        }

        val cacheBody = buildCacheBody(function, innerInvoke, cacheableAnn, cachePutAnn, cacheEvictAnn)
        val body = if (lockKeyExpr != null) {
            val innerBlock = when {
                cacheBody != null -> cacheBody.replaceFirst("return ", "")
                else -> if (serializerExpr != null) {
                    "val _r = $innerInvoke\n                            JsonContent(Json.encodeToString($serializerExpr, _r))"
                } else {
                    innerInvoke
                }
            }
            """
                        val ctx = context.getApplicationContext() ?: throw IllegalStateException("@Lock requires NetonContext")
                        val lockManager = ctx.get(LockManager::class) ?: throw IllegalStateException("LockManager not bound. Install redis { } to enable @Lock.")
                        return lockManager.withLock(
                            key = $lockKeyExpr,
                            ttl = ${ttlMs}L.milliseconds,
                            wait = ${waitMs}L.milliseconds,
                            retryInterval = ${retryMs}L.milliseconds
                        ) {
                            $innerBlock
                        }
"""
        } else {
            if (cacheBody != null) """
                        $cacheBody
""" else """
                        ${wrapWithJsonContent(innerInvoke)}
"""
        }

        writer.write(
            """
        engine.registerRoute(
            RouteDefinition(
                pattern = "$fullPath",
                method = HttpMethod.$httpMethod,
                handler = object : RouteHandler {
                    override suspend fun invoke(context: HttpContext, args: HandlerArgs): Any? {
                        val ctrl = $controllerInstantiation
                        $body
                    }
                },
                controllerClass = "$controllerName",
                methodName = "$methodName",
                allowAnonymous = $allowAnonymous,
                requireAuth = $requireAuth,
                permission = ${if (permission != null) "\"$permission\"" else "null"}
            )
        )
"""
        )
    }

    private fun generateMethodCallParameters(
        function: KSFunctionDeclaration,
        fullPath: String,
        httpMethod: String
    ): String {
        val pathParamNames = Regex("\\{([^}]+)\\}").findAll(fullPath).map { it.groupValues[1] }.toSet()
        val bodyMethods = setOf("POST", "PUT", "PATCH")
        val queryMethods = setOf("GET", "HEAD", "DELETE") + bodyMethods // POST 简单类型也走 query

        return function.parameters.joinToString(", ") { param ->
            val paramName = param.name!!.asString()
            val paramType = param.type.resolve().declaration.qualifiedName!!.asString()
            val isNullable = param.type.resolve().isMarkedNullable

            when {
                paramType == "neton.core.http.HttpContext" || paramType == "neton.core.http.Ctx" -> "context"
                paramType == "neton.core.http.HttpRequest" -> "context.request"
                paramType == "neton.core.http.HttpResponse" -> "context.response"
                paramType == "neton.core.http.HttpSession" -> "context.session"
                paramType == "neton.core.interfaces.Identity" -> {
                    if (isNullable) "context.getAttribute(SecurityAttributes.IDENTITY) as? Identity" else "context.getAttribute(SecurityAttributes.IDENTITY) as Identity"
                }

                paramType == "neton.core.http.UploadFile" -> {
                    if (isNullable) "context.request.uploadFiles().first(\"$paramName\")" else "context.request.uploadFiles().require(\"$paramName\")"
                }

                paramType == "neton.core.http.UploadFiles" -> {
                    "context.request.uploadFiles()"
                }

                paramType == "kotlin.collections.List" && param.type.resolve().arguments.firstOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == "neton.core.http.UploadFile" -> {
                    "context.request.uploadFiles().get(\"$paramName\")"
                }

                else -> {
                    val pathVar = param.annotations.firstOrNull { it.shortName.asString() == "PathVariable" }
                    val queryParam = param.annotations.firstOrNull { it.shortName.asString() == "QueryParam" }
                    val query = param.annotations.firstOrNull { it.shortName.asString() == "Query" }
                    val bodyAnn = param.annotations.firstOrNull { it.shortName.asString() == "Body" }
                    val header = param.annotations.firstOrNull { it.shortName.asString() == "Header" }
                    val cookie = param.annotations.firstOrNull { it.shortName.asString() == "Cookie" }
                    val formParam = param.annotations.firstOrNull { it.shortName.asString() == "FormParam" }
                    val authPrincipal =
                        param.annotations.firstOrNull { it.shortName.asString() == "CurrentUser" || it.shortName.asString() == "AuthenticationPrincipal" }

                    val (actualParamName, isBody) = when {
                        pathVar != null -> (pathVar.arguments.firstOrNull()?.value as? String ?: paramName) to false
                        queryParam != null -> (queryParam.arguments.firstOrNull()?.value as? String
                            ?: paramName) to false

                        query != null -> ((query.arguments.firstOrNull()?.value as? String)?.takeIf { it.isNotEmpty() }
                            ?: paramName) to false

                        bodyAnn != null -> "body" to true
                        header != null -> (header.arguments.firstOrNull()?.value as? String ?: paramName) to false
                        cookie != null -> (cookie.arguments.firstOrNull()?.value as? String ?: paramName) to false
                        formParam != null -> (formParam.arguments.firstOrNull()?.value as? String ?: paramName) to false
                        authPrincipal != null -> "identity" to false
                        else -> {
                            // 约定推断（规范 v1.0.1）
                            val isSimple = paramType in SIMPLE_TYPES
                            when {
                                paramName in pathParamNames -> paramName to false
                                bodyMethods.contains(httpMethod) && !isSimple -> "body" to true
                                else -> paramName to false // query
                            }
                        }
                    }

                    when {
                        isBody -> {
                            val typeName = paramType.substringAfterLast('.')
                            // path="\$" in generated source → literal "$" in JSON (Kotlin string escape)
                            """run {
                                val ct = context.request.contentType?.lowercase() ?: ""
                                if (!ct.contains("application/json")) throw UnsupportedMediaTypeException("Body requires application/json")
                                val raw = context.request.text()
                                val body = try {
                                    Json.decodeFromString($typeName.serializer(), raw)
                                } catch (e: Exception) {
                                    throw ValidationException(listOf(ValidationError(path = "\$", message = "Invalid JSON body", code = "InvalidJson")))
                                }
                                val registry = context.getApplicationContext()?.getOrNull(neton.validation.ValidatorRegistry::class)
                                val validator = registry?.get($typeName::class)
                                val errors = validator?.validate(body)
                                if (!errors.isNullOrEmpty()) throw ValidationException(errors)
                                body
                            }"""
                        }

                        header != null -> {
                            val h = header.arguments.firstOrNull()?.value as? String ?: paramName
                            if (isNullable) "context.request.header(\"$h\")" else "context.request.header(\"$h\") ?: \"\""
                        }

                        cookie != null -> {
                            val c = cookie.arguments.firstOrNull()?.value as? String ?: paramName
                            if (isNullable) "context.request.cookie(\"$c\")?.value" else "context.request.cookie(\"$c\")!!.value"
                        }

                        else -> generateArgConversion(param, actualParamName, paramType, isNullable)
                    }
                }
            }
        }
    }

    /** 歧义检测：多 Body 候选 → 编译失败 */
    private fun checkBodyAmbiguity(
        controller: KSClassDeclaration,
        function: KSFunctionDeclaration,
        httpMethod: String
    ) {
        val bodyMethods = setOf("POST", "PUT", "PATCH")
        val noBodyMethods = setOf("GET", "HEAD", "DELETE")
        val explicitBody = mutableListOf<KSValueParameter>()
        val implicitBodyCandidates = mutableListOf<KSValueParameter>()

        for (param in function.parameters) {
            val typeName = param.type.resolve().declaration.qualifiedName!!.asString()
            val hasBody = param.annotations.any { it.shortName.asString() == "Body" }
            val hasExplicit = param.annotations.any {
                it.shortName.asString() in listOf(
                    "QueryParam",
                    "Query",
                    "FormParam",
                    "Header",
                    "Cookie",
                    "PathVariable"
                )
            }
            val isCtx = typeName in setOf(
                "neton.core.http.HttpContext", "neton.core.http.Ctx", "neton.core.http.HttpRequest",
                "neton.core.http.HttpResponse", "neton.core.http.HttpSession", "neton.core.interfaces.Identity",
                "neton.core.http.UploadFile", "neton.core.http.UploadFiles"
            )
            val isComplex = typeName !in SIMPLE_TYPES && typeName != "kotlin.collections.List" && !isCtx

            when {
                hasBody -> {
                    if (noBodyMethods.contains(httpMethod)) {
                        logger.error(
                            "Neton: GET/DELETE/HEAD with @Body is not allowed. Use POST/PUT or query params.",
                            param
                        )
                    }
                    explicitBody.add(param)
                }

                bodyMethods.contains(httpMethod) && isComplex && !hasExplicit -> implicitBodyCandidates.add(param)
            }
        }

        when {
            explicitBody.size > 1 -> logger.error(
                "Neton binding ambiguity: multiple @Body parameters in ${controller.simpleName.asString()}#${function.simpleName.asString()}. " +
                        "Fix: use a single request DTO.", function
            )

            explicitBody.isEmpty() && implicitBodyCandidates.size > 1 -> logger.error(
                "Neton binding ambiguity: multiple body candidates in ${controller.simpleName.asString()}#${function.simpleName.asString()}. " +
                        "Candidates: ${
                            implicitBodyCandidates.joinToString {
                                "${
                                    it.type.resolve().toString().substringAfterLast('.')
                                } ${it.name!!.asString()}"
                            }
                        }. " +
                        "Fix: annotate body with @Body, or others with @Query/@Form/@Header/@Cookie.", function
            )
        }
    }

    private val SIMPLE_TYPES = setOf(
        "kotlin.String",
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.Boolean",
        "kotlin.Double",
        "kotlin.Float",
        "java.lang.String",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Boolean",
        "java.lang.Double",
        "java.lang.Float"
    )

    /** 单值/List 逐元素 parse（规范 v1.0.2）；必填时先 Missing 再 Type，Int/Long→integer、Double/Float→number */
    private fun generateArgConversion(
        param: com.google.devtools.ksp.symbol.KSValueParameter,
        actualParamName: String,
        paramType: String,
        isNullable: Boolean
    ): String {
        val raw = "args.first(\"$actualParamName\") as? String"
        val rawOrEmpty = "($raw ?: \"\")"
        val hasDefault = param.hasDefault
        val required = !isNullable && !hasDefault
        fun missingThrow() =
            "throw ValidationException(listOf(ValidationError(path = \"$actualParamName\", message = \"is required\", code = \"Missing\")))"

        fun typeThrow(msg: String) =
            "throw ValidationException(listOf(ValidationError(path = \"$actualParamName\", message = \"$msg\", code = \"Type\")))"
        return when (paramType) {
            "kotlin.collections.List" -> {
                val elemType = param.type.element?.typeArguments?.firstOrNull()?.type?.resolve()?.toString()
                    ?.substringAfterLast('.') ?: "String"
                val typeMsg = when (elemType) {
                    "Int", "Long" -> "must be a valid integer"
                    "Double", "Float" -> "must be a valid number"
                    "Boolean" -> "must be true/false/1/0/on/off"
                    else -> "invalid value"
                }
                val parseExpr = when (elemType) {
                    "Int" -> "ParamConverters.parseInt(s) ?: ${typeThrow(typeMsg)}"
                    "Long" -> "ParamConverters.parseLong(s) ?: ${typeThrow(typeMsg)}"
                    "Double" -> "ParamConverters.parseDouble(s) ?: ${typeThrow(typeMsg)}"
                    "Float" -> "ParamConverters.parseDouble(s)?.toFloat() ?: ${typeThrow(typeMsg)}"
                    "Boolean" -> "ParamConverters.parseBoolean(s) ?: ${typeThrow(typeMsg)}"
                    else -> null
                }
                val rawList = "args.all(\"$actualParamName\")"
                if (parseExpr == null) {
                    // List<String> or unsupported: no parse, no fail-fast
                    if (isNullable) "($rawList ?: null)" else if (required) "run { val _raw = $rawList; if (_raw == null) ${missingThrow()}; _raw }" else "($rawList ?: emptyList())"
                } else {
                    // List<Int/Long/Double/Float/Boolean>: fail-fast, 禁止 silent drop（v1 冻结）
                    val bodyLoop = "buildList { for (s in _raw) { add($parseExpr) } }"
                    when {
                        isNullable -> "run { val _raw = $rawList; if (_raw == null) null else $bodyLoop }"
                        required -> "run { val _raw = $rawList; if (_raw == null) ${missingThrow()}; $bodyLoop }"
                        else -> "run { val _raw = $rawList; if (_raw == null) emptyList() else $bodyLoop }"
                    }
                }
            }

            "kotlin.String" -> if (isNullable) raw else if (required) "run { val _r = $raw; if (_r == null) ${missingThrow()}; _r }" else rawOrEmpty
            "kotlin.Int" -> if (isNullable) "ParamConverters.parseInt($rawOrEmpty)" else if (hasDefault) "ParamConverters.parseInt($rawOrEmpty) ?: 0" else "run { val _r = $raw; if (_r == null) ${missingThrow()}; ParamConverters.parseInt(_r ?: \"\") ?: ${
                typeThrow(
                    "must be a valid integer"
                )
            } }"

            "kotlin.Long" -> if (isNullable) "ParamConverters.parseLong($rawOrEmpty)" else if (hasDefault) "ParamConverters.parseLong($rawOrEmpty) ?: 0L" else "run { val _r = $raw; if (_r == null) ${missingThrow()}; ParamConverters.parseLong(_r ?: \"\") ?: ${
                typeThrow(
                    "must be a valid integer"
                )
            } }"

            "kotlin.Boolean" -> if (isNullable) "ParamConverters.parseBoolean($rawOrEmpty)" else if (hasDefault) "ParamConverters.parseBoolean($rawOrEmpty) ?: false" else "run { val _r = $raw; if (_r == null) ${missingThrow()}; ParamConverters.parseBoolean(_r ?: \"\") ?: ${
                typeThrow(
                    "must be true/false/1/0/on/off"
                )
            } }"

            "kotlin.Double" -> if (isNullable) "ParamConverters.parseDouble($rawOrEmpty)" else if (hasDefault) "ParamConverters.parseDouble($rawOrEmpty) ?: 0.0" else "run { val _r = $raw; if (_r == null) ${missingThrow()}; ParamConverters.parseDouble(_r ?: \"\") ?: ${
                typeThrow(
                    "must be a valid number"
                )
            } }"

            "kotlin.Float" -> if (isNullable) "ParamConverters.parseDouble($rawOrEmpty)?.toFloat()" else if (hasDefault) "ParamConverters.parseDouble($rawOrEmpty)?.toFloat() ?: 0f" else "run { val _r = $raw; if (_r == null) ${missingThrow()}; ParamConverters.parseDouble(_r ?: \"\")?.toFloat() ?: ${
                typeThrow(
                    "must be a valid number"
                )
            } }"

            else -> if (isNullable) "($raw as? ${paramType.substringAfterLast('.')})" else if (required) "run { val _r = $raw; if (_r == null) ${missingThrow()}; ($raw as? ${
                paramType.substringAfterLast(
                    '.'
                )
            }) ?: ${typeThrow("invalid value")} }" else "($raw as ${paramType.substringAfterLast('.')}) ?: ${typeThrow("invalid value")}"
        }
    }

    /** 为 @Cacheable / @CachePut / @CacheEvict 生成织入代码；无缓存注解时返回 null */
    private fun buildCacheBody(
        function: KSFunctionDeclaration,
        innerInvoke: String,
        cacheableAnn: KSAnnotation?,
        cachePutAnn: KSAnnotation?,
        cacheEvictAnn: KSAnnotation?
    ): String? {
        val ann = cacheableAnn ?: cachePutAnn ?: cacheEvictAnn ?: return null
        val name = ann.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String ?: return null
        val keyTemplate = (ann.arguments.firstOrNull { it.name?.asString() == "key" }?.value as? String) ?: ""
        val ttlMs = (ann.arguments.firstOrNull { it.name?.asString() == "ttlMs" }?.value as? Long) ?: 0L
        val returnType = function.returnType?.resolve()?.declaration ?: return null
        val returnTypeQualified =
            (returnType as? KSClassDeclaration)?.qualifiedName?.asString() ?: returnType.toString()
        val returnTypeSimple =
            (returnType as? KSClassDeclaration)?.simpleName?.asString() ?: returnTypeQualified.substringAfterLast('.')
        val paramNames = function.parameters.map { it.name!!.asString() }

        val keyExpr = if (keyTemplate.isEmpty()) {
            "neton.cache.CacheKeyHash.stableHash(args, listOf(${paramNames.joinToString(", ") { "\"$it\"" }}))"
        } else {
            resolveLockKeyExpression(keyTemplate)
        }
        val ctxBlock = """
                        val ctx = context.getApplicationContext() ?: throw HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Cache annotations require NetonContext")
                        val cacheManager = ctx.get(neton.cache.CacheManager::class) ?: throw HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "CacheManager not bound. Install cache { } to enable @Cacheable.")
""".trimIndent()
        val ttlExpr = if (ttlMs > 0) "${ttlMs}L.milliseconds" else "null"

        return when {
            cacheableAnn != null -> {
                if (returnTypeQualified == "kotlin.Unit" || returnTypeQualified == "kotlin.Nothing") {
                    logger.warn("@Cacheable on function ${function.simpleName} with return type $returnTypeQualified is not supported (Unit/Nothing); skipping cache weave")
                    return null
                }
                """
                        $ctxBlock
                        val cache = cacheManager.getCache<$returnTypeSimple>("$name")
                        val key = $keyExpr
                        val ttl = $ttlExpr
                        return cache.getOrPut(key, ttl) { $innerInvoke }
""".trimIndent()
            }

            cachePutAnn != null -> {
                if (returnTypeQualified == "kotlin.Unit" || returnTypeQualified == "kotlin.Nothing") {
                    logger.warn("@CachePut on function ${function.simpleName} with return type $returnTypeQualified is not supported; skipping")
                    return null
                }
                """
                        val result = $innerInvoke
                        $ctxBlock
                        val cache = cacheManager.getCache<$returnTypeSimple>("$name")
                        val key = $keyExpr
                        val ttl = $ttlExpr
                        cache.put(key, result, ttl)
                        return result
""".trimIndent()
            }

            cacheEvictAnn != null -> {
                val allEntries =
                    (ann.arguments.firstOrNull { it.name?.asString() == "allEntries" }?.value as? Boolean) ?: false
                """
                        val result = $innerInvoke
                        $ctxBlock
                        val cache = cacheManager.getCache<kotlin.Any?>("$name")
                        ${if (allEntries) "cache.clear()" else "val key = $keyExpr\n                        cache.delete(key)"}
                        return result
""".trimIndent()
            }

            else -> null
        }
    }

    /** 解析 @Lock key 模板 "order:{orderId}" → 生成 "order:" + (args.first("orderId") as? String ?: "") 形式 */
    private fun resolveLockKeyExpression(keyTemplate: String): String {
        val paramNames = Regex("\\{([^}]+)\\}").findAll(keyTemplate).map { it.groupValues[1] }.toList()
        if (paramNames.isEmpty()) return "\"${keyTemplate.replace("\"", "\\\"")}\""
        val parts = keyTemplate.split(Regex("\\{[^}]+\\}"))
        return parts.mapIndexed { i, literal ->
            val escaped = literal.replace("\\", "\\\\").replace("\"", "\\\"")
            if (i < paramNames.size) {
                val p = paramNames[i]
                (if (escaped.isNotEmpty()) "\"$escaped\" + " else "") + "(args.first(\"$p\") as? String ?: \"\")"
            } else "\"$escaped\""
        }.joinToString(" + ")
    }

    private fun getTotalRouteCount(controllers: List<KSClassDeclaration>): Int {
        return controllers.sumOf { controller ->
            controller.getAllFunctions()
                .count { function ->
                    function.annotations.any { annotation ->
                        httpAnnotations.containsKey(annotation.shortName.asString())
                    }
                }
        }
    }

    /**
     * 检查一个 KSType 的声明是否标注了 @Serializable
     */
    private fun isSerializableType(type: KSType): Boolean {
        val decl = type.declaration as? KSClassDeclaration ?: return false
        val qn = decl.qualifiedName?.asString() ?: return false
        if (qn in SIMPLE_TYPES || qn == "kotlin.Unit" || qn == "kotlin.Nothing") return false
        return decl.annotations.any {
            val annQn = (it.annotationType.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
            annQn == "kotlinx.serialization.Serializable"
        }
    }

    /**
     * 构建 kotlinx.serialization 序列化器表达式。
     * 对于 PageResponse<UserVO> 生成: PageResponse.serializer(UserVO.serializer())
     * 对于 UserVO 生成: UserVO.serializer()
     * 对于 List<UserVO> 生成: kotlinx.serialization.builtins.ListSerializer(UserVO.serializer())
     */
    private fun buildSerializerExpression(type: KSType): String? {
        val decl = type.declaration
        val qn = (decl as? KSClassDeclaration)?.qualifiedName?.asString() ?: return null
        val simpleName = qn.substringAfterLast('.')

        // List/Set 等集合
        if (qn == "kotlin.collections.List" || qn == "kotlin.collections.MutableList") {
            val elemType = type.arguments.firstOrNull()?.type?.resolve() ?: return null
            val elemSerializer = buildSerializerExpression(elemType) ?: return null
            return "kotlinx.serialization.builtins.ListSerializer($elemSerializer)"
        }

        if (!isSerializableType(type)) return null

        val typeArgs = type.arguments
        return if (typeArgs.isEmpty()) {
            "$simpleName.serializer()"
        } else {
            val argSerializers = typeArgs.mapNotNull { arg ->
                val argType = arg.type?.resolve() ?: return@mapNotNull null
                buildSerializerExpression(argType)
            }
            if (argSerializers.size != typeArgs.size) return null
            "$simpleName.serializer(${argSerializers.joinToString(", ")})"
        }
    }

    /**
     * 递归收集一个类型及其泛型参数中涉及的所有 @Serializable 类型的完全限定名（用于 import）。
     */
    private fun collectSerializableImports(type: KSType, out: MutableSet<String>) {
        val decl = type.declaration as? KSClassDeclaration ?: return
        val qn = decl.qualifiedName?.asString() ?: return
        if (isSerializableType(type)) {
            out.add(qn)
        }
        type.arguments.forEach { arg ->
            arg.type?.resolve()?.let { collectSerializableImports(it, out) }
        }
    }

    // HTTP 注解映射表
    private val httpAnnotations = mapOf(
        "Get" to "GET",
        "Post" to "POST",
        "Put" to "PUT",
        "Delete" to "DELETE",
        "Patch" to "PATCH",
        "Head" to "HEAD",
        "Options" to "OPTIONS"
    )
}

class ControllerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ControllerProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

/** 将 kebab-case / snake_case / dot-case 转为 PascalCase */
private fun String.toPascalCase(): String {
    return split('-', '_', '.').joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
}
