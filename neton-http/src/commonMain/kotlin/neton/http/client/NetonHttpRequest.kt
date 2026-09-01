package neton.http.client

import neton.core.http.HttpHeaders

/**
 * Request envelope passed to NetonHttpClient.
 *
 * @property metadata caller-supplied tags forwarded to logging/retry hooks (do NOT put secrets here)
 */
data class NetonHttpRequest(
    val method: NetonHttpMethod,
    val url: String,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val body: NetonHttpBody? = null,
    val timeout: NetonHttpTimeout? = null,
    val metadata: Map<String, String> = emptyMap(),
)
