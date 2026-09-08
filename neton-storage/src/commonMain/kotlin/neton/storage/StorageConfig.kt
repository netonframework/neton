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
)
