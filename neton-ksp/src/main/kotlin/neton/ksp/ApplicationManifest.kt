package neton.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import java.security.MessageDigest

internal const val MODULE_ARCHITECTURE = "neton.core.module.ModuleArchitecture"
internal const val MODULE_ROUTE_INVENTORY = "neton.core.module.ModuleRouteInventory"
private const val MODULE_ROUTE = "neton.core.module.ModuleRouteDeclaration"
internal val MODULE_ARCHITECTURE_ID = Regex("[a-z][a-z0-9-]*")

internal data class ManifestModule(
    val id: String,
    val entry: String,
    val declaration: KSClassDeclaration,
    val dependencies: List<String>?,
    val origin: String?,
    val routes: List<ManifestRoute>? = null,
)

internal data class ManifestRoute(
    val method: String,
    val pattern: String,
    val group: String,
    val permission: String?,
    val allowAnonymous: Boolean,
    val requireAuth: Boolean,
    val freshAuth: Boolean,
    val capabilities: List<String>,
    val origin: String,
) {
    fun toAnnotation(): String = "$MODULE_ROUTE(${kotlinString(method)}, ${kotlinString(pattern)}, " +
        "${kotlinString(group)}, ${listOfNotNull(permission).joinToString(", ", "[", "]", transform = ::kotlinString)}, " +
        "$allowAnonymous, $requireAuth, $freshAuth, " +
        "${capabilities.joinToString(", ", "[", "]", transform = ::kotlinString)}, ${kotlinString(origin)})"
}

internal fun sourceOrigin(node: KSNode, name: String): String {
    val location = node.location as? FileLocation ?: return name
    return "$name (${location.filePath.replace('\\', '/').substringAfterLast('/')}:${location.lineNumber})"
}

internal fun KSAnnotation.manifestRoute(): ManifestRoute {
    val values = arguments.associate { it.name?.asString() to it.value }
    fun string(name: String) = values[name] as? String ?: error(name)
    fun flag(name: String) = values[name] as? Boolean ?: error(name)
    fun strings(name: String) = (values[name] as? List<*> ?: error(name)).map { it as? String ?: error(name) }
    val permissions = strings("permissions")
    check(permissions.size <= 1)
    return ManifestRoute(string("method"), string("pattern"), string("group"), permissions.singleOrNull(),
        flag("allowAnonymous"), flag("requireAuth"), flag("freshAuth"), strings("capabilities"), string("origin"))
}

private data class RouteKey(val group: String, val method: String, val pattern: List<String>)

internal fun checkArchitecture(
    modules: List<ManifestModule>,
    logger: KSPLogger,
    providedCapabilities: Set<String>?,
): Boolean {
    val byId = modules.associateBy { it.id }
    for (module in modules) {
        val seen = mutableSetOf<String>()
        for (dependency in module.dependencies.orEmpty()) {
            val rule = when {
                !MODULE_ARCHITECTURE_ID.matches(dependency) -> "NETON_ARCH_INVALID_DEPENDENCY"
                !seen.add(dependency) -> "NETON_ARCH_DUPLICATE_DEPENDENCY"
                dependency !in byId -> "NETON_ARCH_MISSING_DEPENDENCY"
                else -> continue
            }
            logger.error("$rule: ${module.id} -> $dependency (${module.origin ?: module.entry})", module.declaration)
            return false
        }
    }

    val finished = mutableSetOf<String>()
    val path = linkedSetOf<String>()
    fun visit(id: String): Boolean {
        if (id in finished) return true
        val module = byId.getValue(id)
        if (!path.add(id)) {
            val cycle = (path.dropWhile { it != id } + id).joinToString(" -> ")
            logger.error("NETON_ARCH_MODULE_CYCLE: $cycle (${module.origin ?: module.entry})", module.declaration)
            return false
        }
        for (dep in module.dependencies.orEmpty()) if (!visit(dep)) return false
        path.remove(id)
        finished.add(id)
        return true
    }
    if (!modules.all { visit(it.id) }) return false
    val registered = mutableMapOf<RouteKey, Pair<ManifestModule, ManifestRoute>>()
    for (module in modules) for (route in module.routes.orEmpty()) {
        val shape = route.pattern.substringBefore('?').split('/').filter(String::isNotEmpty).map {
            if (it.startsWith("{") && it.endsWith("}")) "{}" else it
        }
        val key = RouteKey(route.group, route.method, shape)
        val previous = registered.putIfAbsent(key, module to route)
        if (previous != null) {
            logger.error(
                "NETON_ARCH_DUPLICATE_ROUTE: ${route.method} ${route.pattern} " +
                    "in group '${route.group.ifEmpty { "<default>" }}': " +
                    "${previous.first.id} (${previous.second.origin}) and ${module.id} (${route.origin})",
                module.declaration,
            )
            return false
        }
        if (providedCapabilities != null) {
            val missing = route.capabilities.filterNot(providedCapabilities::contains)
            if (missing.isNotEmpty()) {
                logger.error("NETON_ARCH_MISSING_CAPABILITY: ${missing.joinToString()} for " +
                    "${route.method} ${route.pattern} in ${module.id} (${route.origin})", module.declaration)
                return false
            }
        }
    }
    return true
}

internal fun architectureJson(modules: List<ManifestModule>, providedCapabilities: Set<String>?): String = buildString {
    append("""{"schemaVersion":2,"declaredHttpCapabilities":""")
    append(providedCapabilities?.sorted()?.joinToString(",", "[", "]", transform = ::jsonString) ?: "null")
    append(""","modules":[""")
    modules.forEachIndexed { index, module ->
        if (index != 0) append(',')
        append("""{"id":${jsonString(module.id)},"entry":${jsonString(module.entry)},"origin":""")
        append(module.origin?.let(::jsonString) ?: "null")
        append(""","declaredDependencies":""")
        append(module.dependencies?.joinToString(",", "[", "]", transform = ::jsonString) ?: "null")
        append(""","routes":""")
        append(module.routes?.joinToString(",", "[", "]") { routeJson(it) } ?: "null")
        append('}')
    }
    append("]}")
}

private fun routeJson(route: ManifestRoute): String = with(route) {
    """{"method":${jsonString(method)},"pattern":${jsonString(pattern)},"group":""" +
        (group.takeIf(String::isNotEmpty)?.let(::jsonString) ?: "null") +
        ""","permission":${permission?.let(::jsonString) ?: "null"},"allowAnonymous":$allowAnonymous,"requireAuth":$requireAuth,"freshAuth":$freshAuth""" +
        ""","capabilities":${capabilities.joinToString(",", "[", "]", transform = ::jsonString)},"origin":${jsonString(origin)}}"""
}

internal fun architectureDigest(json: String): String =
    MessageDigest.getInstance("SHA-256").digest(json.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun kotlinString(value: String): String = jsonString(value).replace("$", "\\$")

private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
