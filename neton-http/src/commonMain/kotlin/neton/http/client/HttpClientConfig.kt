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

    /** HTTP(S) 代理地址，如 "http://host:port"。仅支持 HTTP 代理（CIO 引擎不支持 SOCKS）。 */
    var proxyUrl: String? = null

    /**
     * 对单个主机的并发连接上限。引擎默认 8；池满时**直接以连接失败拒绝**而不是排队，
     * 所以对同一下游（如 IM service API）扇出超过 8 路并发的应用必须调高，否则表现为
     * 偶发的 "connect: connect failed"。
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
