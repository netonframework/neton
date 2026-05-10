package neton.http.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp

internal actual fun platformHttpClient(
    applyConfig: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(WinHttp) { applyConfig() }
