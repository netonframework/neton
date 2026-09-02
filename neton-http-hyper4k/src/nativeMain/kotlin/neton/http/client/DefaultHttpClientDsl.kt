package neton.http.client

import neton.http.hyper4k.createHyper4kHttpClient

/**
 * The no-argument entry point: `HttpClient.create { requestMillis = 30_000 }`.
 *
 * The client half of what DefaultHttpDsl does for the server (spec
 * zh-hans/spec/http-engine.md rule 1): one engine module ships both entry
 * points, declared in the contract layer's packages, so an application never
 * names an engine in source. Depending on this module is what makes hyper4k
 * the default outbound client.
 */
fun HttpClient.Companion.create(block: HttpClientConfig.() -> Unit = {}): HttpClient =
    createWith(::createHyper4kHttpClient, block)
