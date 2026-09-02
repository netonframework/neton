package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Per-platform Ktor engine selection.
 *  - macOS           → CIO (Darwin/NSURLSession failed the client conformance suite)
 *  - Linux (posix)   → CIO engine
 *  - Windows         → WinHttp engine
 *
 * Returns the engine factory; engine is instantiated by [KtorHttpClient].
 */
internal expect fun defaultKtorEngine(): HttpClientEngineFactory<*>
