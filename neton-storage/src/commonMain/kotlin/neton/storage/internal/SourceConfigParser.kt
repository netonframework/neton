package neton.storage.internal

import neton.storage.SourceConfig
import neton.storage.SourceOverrides

internal object SourceConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parseSourceConfigs(raw: Map<String, Any?>): List<SourceConfig> {
        val sources = raw["sources"] as? List<*>
            ?: throw IllegalStateException("storage.conf: missing [[sources]]")
        if (sources.isEmpty()) {
            throw IllegalStateException("storage.conf: [[sources]] is empty")
        }
        return sources.map { item ->
            val m = item as? Map<String, Any?>
                ?: throw IllegalStateException("storage.conf: invalid source entry")
            fun string(key: String, default: String): String {
                if (!m.containsKey(key)) return default
                return m[key] as? String
                    ?: throw IllegalStateException("storage.conf: '$key' must be a string")
            }
            val name = m["name"] as? String
                ?: throw IllegalStateException("storage.conf: source missing 'name'")
            if (name.isBlank()) {
                throw IllegalStateException("storage.conf: source 'name' cannot be blank")
            }
            SourceConfig(
                name = name,
                type = string("type", "local"),
                baseUrl = string("baseUrl", ""),
                basePath = string("basePath", "./uploads"),
                endpoint = string("endpoint", ""),
                region = string("region", ""),
                bucket = string("bucket", ""),
                accessKey = string("accessKey", ""),
                secretKey = string("secretKey", ""),
                pathStyle = if (!m.containsKey("pathStyle")) false else m["pathStyle"] as? Boolean
                    ?: throw IllegalStateException("storage.conf: 'pathStyle' must be a boolean")
            )
        }
    }

    private fun validateNames(sources: List<SourceConfig>) {
        val names = mutableSetOf<String>()
        for (src in sources) {
            if (src.name.isBlank()) {
                throw IllegalStateException("storage.conf: source 'name' cannot be blank")
            }
            if (!names.add(src.name)) {
                throw IllegalStateException("storage.conf: duplicate source name '${src.name}'")
            }
        }
    }

    fun validateSources(sources: List<SourceConfig>) {
        validateNames(sources)
        if (sources.none { it.name == "default" }) {
            throw IllegalStateException("storage.conf: missing source with name='default'")
        }
    }

    fun mergeSources(dsl: List<SourceConfig>, file: List<SourceConfig>, overrides: List<SourceOverrides> = emptyList()): List<SourceConfig> {
        validateNames(dsl + overrides.map { SourceConfig(name = it.name) })
        validateNames(file)
        val dslByName = dsl.associateBy { it.name }
        val fileByName = file.associateBy { it.name }
        val overridesByName = overrides.associateBy { it.name }
        val allNames = LinkedHashSet<String>()
        file.forEach { allNames.add(it.name) }
        dsl.forEach { allNames.add(it.name) }
        overrides.forEach { allNames.add(it.name) }

        return allNames.map { name ->
            val d = dslByName[name]
            val f = fileByName[name]
            val base = d ?: f ?: SourceConfig(name = name)
            overridesByName[name]?.resolve(base) ?: base.copy()
        }
    }
}
