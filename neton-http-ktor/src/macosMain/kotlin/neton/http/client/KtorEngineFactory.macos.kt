package neton.http.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

/**
 * CIO, not Darwin. The client conformance suite showed NSURLSession folding
 * repeated Set-Cookie headers into one value, holding chunked bodies back
 * instead of streaming them, and leaving the connection open after Flow
 * cancellation. CIO passes on every POSIX target, so macOS and Linux now run
 * the same engine and the same behaviour.
 */
internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = CIO
