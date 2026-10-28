package neton.storage

data class StorageConfig(
    /** Complete source replacements; use source() for field-level overrides. */
    val sources: MutableList<SourceConfig> = mutableListOf(),
    val overrides: MutableList<SourceOverrides> = mutableListOf()
) {
    fun source(name: String, block: SourceOverrides.() -> Unit) {
        overrides.add(SourceOverrides(name = name).apply(block).copy())
    }
}

/** null means inherit; false and empty strings are explicit overrides. */
data class SourceOverrides(
    val name: String = "default",
    var type: String? = null,
    var baseUrl: String? = null,
    var basePath: String? = null,
    var endpoint: String? = null,
    var region: String? = null,
    var bucket: String? = null,
    var accessKey: String? = null,
    var secretKey: String? = null,
    var pathStyle: Boolean? = null
) {
    internal fun resolve(base: SourceConfig = SourceConfig(name = name)): SourceConfig = SourceConfig(
        name = name, type = type ?: base.type, baseUrl = baseUrl ?: base.baseUrl,
        basePath = basePath ?: base.basePath, endpoint = endpoint ?: base.endpoint,
        region = region ?: base.region, bucket = bucket ?: base.bucket,
        accessKey = accessKey ?: base.accessKey, secretKey = secretKey ?: base.secretKey,
        pathStyle = pathStyle ?: base.pathStyle
    )

    override fun toString(): String = "SourceOverrides(name=$name, type=$type, accessKey=[REDACTED], secretKey=[REDACTED])"
}

data class SourceConfig(
    var name: String = "default",
    var type: String = "local",

    /**
     * 该存储源的公开访问基址，例如 `https://weey-125.cos.ap-guangzhou.myqcloud.com`
     * 或一个 CDN 域名。**下发给客户端的地址 = baseUrl + 对象路径**。
     *
     * 数据库里只存桶内相对路径（主机名是部署事实，换 CDN / 换区域 / 不同品牌用不同的桶
     * 都不该反过来改已有的每一行），拼接发生在返回给客户端的那一刻。
     *
     * 留空 = 没有公开地址（纯内部读写的源）。业务若需要下发 URL，自己在启动期校验非空
     * 并 fail-fast——运行期退回相对路径是最糟的选择：客户端拿到一个下载不了的地址，
     * 而且这种错要等到用户看不到图才被发现。
     */
    var baseUrl: String = "",

    // Local
    var basePath: String = "./uploads",

    // S3
    var endpoint: String = "",
    var region: String = "",
    var bucket: String = "",
    var accessKey: String = "",
    var secretKey: String = "",
    var pathStyle: Boolean = false
) {
    override fun toString(): String = "SourceConfig(name=$name, type=$type, accessKey=[REDACTED], secretKey=[REDACTED])"
}
