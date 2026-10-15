package neton.ai.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class HeaderBinding(val headerName: String, val path: List<String>, val valueType: String)

/** modern 协议的请求头规则（`x-mcp-header` 参数头与值编码）。 */
internal object McpHeaders {
    private val HTTP_TOKEN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val BASE64_SENTINEL = Regex("^=\\?base64\\?.*\\?=$")

    sealed interface Bindings {
        data class Ok(val bindings: List<HeaderBinding>) : Bindings
        data class Invalid(val reason: String) : Bindings
    }

    /**
     * 头值编码：可打印 ASCII、首尾无空白、且不像编码哨兵时原样发送；否则发送 `=?base64?<UTF-8 的 base64>?=`。
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun encode(value: String): String {
        val plain = value.all { it == '\t' || it.code in 0x20..0x7e }
        if (plain && value.trim() == value && !BASE64_SENTINEL.matches(value)) return value
        return "=?base64?${kotlin.io.encoding.Base64.encode(value.encodeToByteArray())}?="
    }

    /**
     * 从工具的 inputSchema 找出 `x-mcp-header` 标注：只能标在从根经 `properties` 静态可达的
     * boolean / integer / string 属性上，头名是 HTTP token 且不重复（不区分大小写）。任一不合法则整个工具无效。
     */
    fun bindings(schema: JsonObject): Bindings {
        val out = mutableListOf<HeaderBinding>()
        val names = HashSet<String>()
        var error: String? = null
        fun visit(v: JsonElement, path: List<String>, reachable: Boolean) {
            if (error != null) return
            if (v is JsonArray) { v.forEach { visit(it, path, false) }; return }
            if (v !is JsonObject) return
            v["x-mcp-header"]?.let { h ->
                val name = (h as? JsonPrimitive)?.takeIf { it.isString }?.content
                val type = (v["type"] as? JsonPrimitive)?.contentOrNull
                error = when {
                    !reachable || path.isEmpty() -> "x-mcp-header is not on a statically reachable property"
                    name == null || !HTTP_TOKEN.matches(name) -> "x-mcp-header must be a non-empty HTTP token"
                    !names.add(name.lowercase()) -> "x-mcp-header value \"$name\" is not unique"
                    type !in setOf("boolean", "integer", "string") -> "x-mcp-header can only annotate boolean, integer, or string properties"
                    else -> { out += HeaderBinding(name, path, type!!); null }
                }
            }
            for ((k, child) in v) {
                if (k == "x-mcp-header") continue
                if (k == "properties" && child is JsonObject) child.forEach { (p, s) -> visit(s, path + p, reachable) }
                else visit(child, path, false)
            }
        }
        visit(schema, emptyList(), true)
        return error?.let { Bindings.Invalid(it) } ?: Bindings.Ok(out)
    }

    /** 按绑定从参数取值生成 `Mcp-Param-<名>`；值类型不符时报错（参数缺失则不发送该头）。 */
    fun toolHeaders(bindings: List<HeaderBinding>, args: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (b in bindings) {
            var cur: JsonElement? = args
            for (seg in b.path) cur = (cur as? JsonObject)?.get(seg)
            val p = cur as? JsonPrimitive ?: continue
            if (p is kotlinx.serialization.json.JsonNull) continue
            val ok = when (b.valueType) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                else -> !p.isString && p.longOrNull != null
            }
            if (!ok) throw McpException(McpErrorKind.InvalidArgument, "tool argument \"${b.path.joinToString(".")}\" does not match its x-mcp-header type")
            out["Mcp-Param-${b.headerName}"] = encode(p.content)
        }
        return out
    }
}
