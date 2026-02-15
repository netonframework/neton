package neton.storage.internal

import neton.storage.SourceConfig

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
            val name = m["name"]?.toString()
                ?: throw IllegalStateException("storage.conf: source missing 'name'")
            if (name.isBlank()) {
                throw IllegalStateException("storage.conf: source 'name' cannot be blank")
            }
            SourceConfig(
                name = name,
                type = m["type"]?.toString() ?: "local",
                basePath = m["basePath"]?.toString() ?: "./uploads",
                endpoint = m["endpoint"]?.toString() ?: "",
                region = m["region"]?.toString() ?: "",
                bucket = m["bucket"]?.toString() ?: "",
                accessKey = m["accessKey"]?.toString() ?: "",
                secretKey = m["secretKey"]?.toString() ?: "",
                pathStyle = m["pathStyle"] as? Boolean ?: false
            )
        }
    }

    fun validateSources(sources: List<SourceConfig>) {
        val names = mutableSetOf<String>()
        for (src in sources) {
            if (src.name.isBlank()) {
                throw IllegalStateException("storage.conf: source 'name' cannot be blank")
            }
            if (!names.add(src.name)) {
                throw IllegalStateException("storage.conf: duplicate source name '${src.name}'")
            }
        }
        if ("default" !in names) {
            throw IllegalStateException("storage.conf: missing source with name='default'")
        }
    }

    fun mergeSources(dsl: List<SourceConfig>, file: List<SourceConfig>): List<SourceConfig> {
        val dslByName = dsl.associateBy { it.name }
        val fileByName = file.associateBy { it.name }
        val allNames = LinkedHashSet<String>()
        file.forEach { allNames.add(it.name) }
        dsl.forEach { allNames.add(it.name) }

        return allNames.map { name ->
            val d = dslByName[name]
            val f = fileByName[name]
            when {
                d != null && f != null -> SourceConfig(
                    name = name,
                    type = if (d.type != "local") d.type else f.type,
                    basePath = if (d.basePath != "./uploads") d.basePath else f.basePath,
                    endpoint = d.endpoint.ifBlank { f.endpoint },
                    region = d.region.ifBlank { f.region },
                    bucket = d.bucket.ifBlank { f.bucket },
                    accessKey = d.accessKey.ifBlank { f.accessKey },
                    secretKey = d.secretKey.ifBlank { f.secretKey },
                    pathStyle = d.pathStyle || f.pathStyle
                )

                d != null -> d
                else -> f!!
            }
        }
    }
}
