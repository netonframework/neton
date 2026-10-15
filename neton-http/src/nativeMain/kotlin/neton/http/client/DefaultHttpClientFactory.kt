package neton.http.client

import neton.http.engine.default.client.createNetonStreamHttpClient

internal actual fun createDefaultHttpClient(config: HttpClientConfig): HttpClient =
    createNetonStreamHttpClient(config)
