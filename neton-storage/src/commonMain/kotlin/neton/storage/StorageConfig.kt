package neton.storage

data class StorageConfig(
    val sources: MutableList<SourceConfig> = mutableListOf()
) {
    fun source(name: String, block: SourceConfig.() -> Unit) {
        sources.add(SourceConfig(name = name).apply(block))
    }
}

data class SourceConfig(
    var name: String = "default",
    var type: String = "local",

    // Local
    var basePath: String = "./uploads",

    // S3
    var endpoint: String = "",
    var region: String = "",
    var bucket: String = "",
    var accessKey: String = "",
    var secretKey: String = "",
    var pathStyle: Boolean = false
)
