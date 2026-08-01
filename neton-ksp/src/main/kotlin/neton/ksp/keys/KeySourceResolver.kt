package neton.ksp.keys

/**
 * `@Lock` / `@Cacheable` 的 key 来源判定，与 KSP 符号模型解耦，可直接单测。
 *
 * 判定的全部意义在于：生成的 key 表达式运行时只能读 `HandlerArgs`，而它只装 path 和 query。
 * 其他来源（body、header、cookie、表单、注入的框架类型）取到 null，参与 hash 等于没参与——
 * 两个只有 body 不同的请求会算出同一个 key，`@Cacheable` 就会把 A 的响应返回给 B。
 * 而且 args 里的键名是**绑定名**：`@PathVariable("id") userId` 的键是 `id`，不是 `userId`。
 */
internal object KeySourceResolver {

    /** 由框架注入、不来自 path/query 的参数类型 */
    val INJECTED_PARAM_TYPES = setOf(
        "neton.core.http.HttpContext",
        "neton.core.http.Ctx",
        "neton.core.http.HttpRequest",
        "neton.core.http.HttpResponse",
        "neton.core.http.HttpSession",
        "neton.core.interfaces.Identity",
        "neton.core.http.UploadFile",
        "neton.core.http.UploadFiles",
    )

    /** 标注了这些注解的参数不进 HandlerArgs */
    val NON_ARGS_PARAM_ANNOTATIONS = setOf(
        "Body", "Header", "Cookie", "FormParam", "CurrentUser", "AuthenticationPrincipal",
    )

    val SIMPLE_TYPES = setOf(
        "kotlin.String", "kotlin.Int", "kotlin.Long",
        "kotlin.Boolean", "kotlin.Double", "kotlin.Float",
    )

    private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

    /** 一个 handler 参数，只保留判定 key 来源需要的信息 */
    data class KeyParam(
        val kotlinName: String,
        val typeQualified: String,
        /** 参数上出现的注解简单名 */
        val annotations: Set<String> = emptySet(),
        /** `@PathVariable("id")` 里的 "id"；注解不存在或无参时为 null */
        val pathVariableAlias: String? = null,
        val queryParamAlias: String? = null,
        val queryAlias: String? = null,
        val isUploadFileList: Boolean = false,
    )

    sealed interface KeyProblem {
        /** 默认 hash 里混入了取不到值的参数 */
        data class UnusableParams(val kotlinNames: List<String>, val available: List<String>) : KeyProblem

        /** key 模板引用了 args 里不存在的名字 */
        data class UnresolvablePlaceholders(
            val placeholders: List<String>,
            val available: List<String>,
            /** Kotlin 参数名 → 应当改写成的绑定名 */
            val aliasHints: Map<String, String>,
        ) : KeyProblem
    }

    /**
     * Kotlin 参数名 → args 里的键名；值为 null 表示这个参数在 key 计算时取不到。
     * 顺序与参数声明顺序一致，默认 hash 依赖它保持稳定。
     */
    fun resolveArgsNames(
        params: List<KeyParam>,
        pathParamNames: Set<String>,
        httpMethod: String,
    ): Map<String, String?> = params.associate { p ->
        p.kotlinName to when {
            p.typeQualified in INJECTED_PARAM_TYPES || p.isUploadFileList -> null
            p.annotations.any { it in NON_ARGS_PARAM_ANNOTATIONS } -> null
            "PathVariable" in p.annotations -> p.pathVariableAlias ?: p.kotlinName
            "QueryParam" in p.annotations -> p.queryParamAlias ?: p.kotlinName
            "Query" in p.annotations -> p.queryAlias?.takeIf { it.isNotEmpty() } ?: p.kotlinName
            p.kotlinName in pathParamNames -> p.kotlinName
            // 约定推断：body 方法上的复杂类型走 body，其余当 query
            httpMethod.uppercase() in BODY_METHODS && p.typeQualified !in SIMPLE_TYPES -> null
            else -> p.kotlinName
        }
    }

    /** 提取 key 模板里的 `{name}` 占位符 */
    fun placeholdersOf(keyTemplate: String): List<String> =
        Regex("\\{([^}]+)\\}").findAll(keyTemplate).map { it.groupValues[1] }.toList()

    /**
     * @param keyTemplate 空串表示走默认的 hash(所有参数)
     * @return 发现的问题；为空表示这个 key 能真正区分请求
     */
    fun validate(keyTemplate: String, argsNames: Map<String, String?>): List<KeyProblem> {
        val available = argsNames.values.filterNotNull()
        if (keyTemplate.isEmpty()) {
            val unusable = argsNames.filterValues { it == null }.keys.toList()
            return if (unusable.isEmpty()) emptyList()
            else listOf(KeyProblem.UnusableParams(unusable, available))
        }
        val unresolvable = placeholdersOf(keyTemplate).filterNot { it in available }
        if (unresolvable.isEmpty()) return emptyList()

        // 参数用别名绑定时，key 必须写别名——那才是运行时 args 里的键
        val aliasHints = argsNames
            .filter { (kotlin, args) -> args != null && args != kotlin && kotlin in unresolvable }
            .map { (kotlin, args) -> kotlin to args!! }
            .toMap()
        return listOf(KeyProblem.UnresolvablePlaceholders(unresolvable, available, aliasHints))
    }

    /** 默认 hash 应当传给 CacheKeyHash.stableHash 的键名（按声明顺序） */
    fun hashKeyNames(argsNames: Map<String, String?>): List<String> = argsNames.values.filterNotNull()

    fun describe(problem: KeyProblem, annotationLabel: String, where: String): String = when (problem) {
        is KeyProblem.UnusableParams ->
            "Neton $annotationLabel on $where: parameters ${problem.kotlinNames.joinToString(", ")} cannot take part " +
                "in the default key because only path and query values are available at key time. Two requests that " +
                "differ only in those parameters would share one key. Give the annotation an explicit " +
                "key = \"...\" built from path/query parameters."

        is KeyProblem.UnresolvablePlaceholders -> {
            val hint = problem.aliasHints.entries.joinToString(", ") { (kotlin, args) ->
                "use {$args} instead of {$kotlin}"
            }
            "Neton $annotationLabel on $where: key placeholder(s) " +
                problem.placeholders.joinToString(", ") { "{$it}" } +
                " do not name a path or query value of this handler, so they would resolve to an empty string. " +
                "Nested paths such as {user.id} are not supported. Available: " +
                (problem.available.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(none)") +
                (if (hint.isNotEmpty()) ". Note: $hint" else "")
        }
    }
}
