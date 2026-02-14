package neton.core.http

/**
 * Handler 参数视图（规范 v1.0.2）
 * 只读，path 与 query 分离，查找时 path 优先，避免 merge 大 Map
 */
interface HandlerArgs {
    /** 单值：path 优先，否则 query 首个 */
    fun first(name: String): Any?
    /** 多值：仅 query，path 不参与 */
    fun all(name: String): List<String>?
    /** Map 兼容：等价于 first(name) */
    operator fun get(name: String): Any? = first(name)
}

/**
 * Path + Query 分离视图，零 merge
 */
class ArgsView(
    private val path: Map<String, String>,
    private val query: Map<String, List<String>>
) : HandlerArgs {
    override fun first(name: String): Any? =
        path[name] ?: query[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

    override fun all(name: String): List<String>? = query[name]
}

/**
 * Map 兼容适配器（routing 等仍用 Map 时）
 */
class MapBackedHandlerArgs(private val map: Map<String, Any?>) : HandlerArgs {
    override fun first(name: String): Any? = map[name]
    override fun all(name: String): List<String>? = (map[name] as? List<*>)?.mapNotNull { it?.toString() }
}
