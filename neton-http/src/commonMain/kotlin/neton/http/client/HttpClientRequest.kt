package neton.http.client

import neton.core.http.HttpHeaders

/**
 * Request envelope passed to HttpClient.
 *
 * @property metadata caller-supplied tags forwarded to logging/retry hooks (do NOT put secrets here)
 */
data class HttpClientRequest(
    val method: HttpClientMethod,
    val url: String,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val body: HttpClientBody? = null,
    val timeout: HttpClientTimeouts? = null,
    val metadata: Map<String, String> = emptyMap(),
)
