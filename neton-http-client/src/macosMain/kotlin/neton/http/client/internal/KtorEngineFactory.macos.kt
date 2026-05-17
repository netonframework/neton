package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = Darwin
