package neton.http.client

import neton.core.annotations.InternalNetonApi

/**
 * DSL config for HttpClient.create { ... }.
 * Nullable fields = "not set" (let defaults or file config fill in).
 */
class HttpClientConfig {
    var connectMillis: Long? = null
    var requestMillis: Long? = null
    var socketMillis: Long? = null
    var debug: Boolean = false

    /**
     * HTTP(S) 代理地址，如 "http://host:port"（可带 `user:pass@`，按 Basic 发 Proxy-Authorization）。
     * https 目标走 CONNECT 隧道，http 目标走 absolute-form。不支持 SOCKS。
     */
    var proxyUrl: String? = null

    /**
     * 对单个 `scheme://authority` 同时打开的连接上限。未设置时内置客户端不限（HTTP/1 每个在途请求
     * 一条连接，HTTP/2 一条连接多路复用）；设置后，已满时新连接**直接以 Network 错误拒绝**而不是排队，
     * 所以设了它的应用要按对同一下游的最大并发来取值。Ktor 客户端未设置时默认 8。
     */
    var maxConnectionsPerHost: Int? = null

    /**
     * 出站地址策略：设置后，每条新连接都按 [OutboundAddressPolicy] 校验 scheme / 端口 / 解析出的**全部**地址，
     * 只连校验过的地址（防 DNS 解析到内网与解析结果在校验和连接之间被换掉）。给抓取用户 / 外部内容 URL 的客户端用；
     * 地址固定在部署配置里的客户端不需要。可与 [proxyUrl] 同时设置：目标仍由客户端解析并校验，代理只会收到校验过的
     * IP（https 为 `CONNECT <ip>:<port>`，http 为指向 IP 的 absolute-form、`Host` 保留原主机）；代理地址本身按
     * [OutboundAddressPolicy.admitProxy] 校验（内网代理须在白名单里）。需要客户端声明 [HttpClientCapability.ADDRESS_POLICY]。
     */
    var addressPolicy: OutboundAddressPolicy? = null

    /**
     * 自定义域名解析。未设置时用平台解析器。设置后客户端解析一次、只连解析出的地址（与 [addressPolicy] 同一条路径）。
     * 主要给测试注入「解析到内网」「两次解析结果不同」这类答案。需要 [HttpClientCapability.ADDRESS_POLICY]。
     */
    var hostResolver: HostResolver? = null

    /** Public only so client adapter modules can resolve the same defaults. */
    @InternalNetonApi
    fun toEffectiveTimeout(): HttpClientTimeouts = HttpClientTimeouts(
        connectMillis = connectMillis ?: 5_000,
        requestMillis = requestMillis ?: 60_000,
        socketMillis = socketMillis ?: 60_000,
    )

    internal fun validate(): List<String> {
        val errors = mutableListOf<String>()
        connectMillis?.let { if (it <= 0) errors += "connectMillis must be > 0" }
        requestMillis?.let { if (it <= 0) errors += "requestMillis must be > 0" }
        socketMillis?.let { if (it <= 0) errors += "socketMillis must be > 0" }
        maxConnectionsPerHost?.let { if (it <= 0) errors += "maxConnectionsPerHost must be > 0" }
        proxyUrl?.let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) {
                errors += "proxyUrl must be an http(s) URL: $it"
            }
        }
        return errors
    }
}
