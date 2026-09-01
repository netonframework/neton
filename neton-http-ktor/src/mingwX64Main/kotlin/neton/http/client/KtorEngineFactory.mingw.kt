package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = WinHttp
