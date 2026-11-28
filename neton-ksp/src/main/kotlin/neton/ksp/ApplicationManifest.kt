package neton.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.security.MessageDigest

internal const val MODULE_ARCHITECTURE = "neton.core.module.ModuleArchitecture"
internal val MODULE_ARCHITECTURE_ID = Regex("[a-z][a-z0-9-]*")

internal data class ManifestModule(
    val id: String,
    val entry: String,
    val declaration: KSClassDeclaration,
    val dependencies: List<String>?,
    val origin: String?,
)

internal fun checkArchitecture(modules: List<ManifestModule>, logger: KSPLogger): Boolean {
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
    return modules.all { visit(it.id) }
}

internal fun architectureJson(modules: List<ManifestModule>): String = buildString {
    append("""{"schemaVersion":1,"modules":[""")
    modules.forEachIndexed { index, module ->
        if (index != 0) append(',')
        append("""{"id":${jsonString(module.id)},"entry":${jsonString(module.entry)},"origin":""")
        append(module.origin?.let(::jsonString) ?: "null")
        append(""","declaredDependencies":""")
        append(module.dependencies?.joinToString(",", "[", "]", transform = ::jsonString) ?: "null")
        append('}')
    }
    append("]}")
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
