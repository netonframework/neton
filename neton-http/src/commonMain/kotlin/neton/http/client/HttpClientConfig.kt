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
