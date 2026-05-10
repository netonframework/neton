package neton.http.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

internal actual fun platformHttpClient(
    applyConfig: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO) { applyConfig() }
