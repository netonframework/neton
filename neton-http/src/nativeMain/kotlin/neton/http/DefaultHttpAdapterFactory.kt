package neton.http

import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpServerConfig
import neton.http.engine.default.DefaultHttpAdapter

internal actual fun createDefaultHttpAdapter(config: HttpServerConfig): HttpAdapter =
    DefaultHttpAdapter(config)
