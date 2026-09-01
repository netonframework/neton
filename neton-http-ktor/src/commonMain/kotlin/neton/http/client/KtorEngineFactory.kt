package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Per-platform Ktor engine selection.
 *  - macOS (Darwin)  → Darwin engine (NSURLSession)
 *  - Linux (posix)   → CIO engine
 *  - Windows         → WinHttp engine
 *
 * Returns the engine factory; engine is instantiated by [DefaultNetonHttpClient].
 */
internal expect fun defaultKtorEngine(): HttpClientEngineFactory<*>
