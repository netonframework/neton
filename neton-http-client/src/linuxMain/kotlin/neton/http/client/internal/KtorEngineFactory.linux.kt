package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = CIO
