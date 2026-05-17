package neton.http.client

/**
 * Request envelope passed to NetonHttpClient.
 *
 * @property metadata caller-supplied tags forwarded to logging/retry hooks (do NOT put secrets here)
 */
data class NetonHttpRequest(
    val method: NetonHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: NetonHttpBody? = null,
    val timeout: NetonHttpTimeout? = null,
    val metadata: Map<String, String> = emptyMap(),
)
