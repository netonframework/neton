package neton.http.client

/**
 * The no-argument entry point: `HttpClient.create { requestMillis = 30_000 }`.
 *
 * The client half of what the no-argument `http { }` does for the server (spec
 * zh-hans/spec/http-engine.md §4): neton-http ships the built-in netonstream engine for both
 * directions, so an application gets a working outbound client from the framework alone and never
 * names an engine. Another engine is chosen explicitly, in source, with
 * `HttpClient.createWith(::factory) { }` — there is exactly one `create`.
 */
fun HttpClient.Companion.create(block: HttpClientConfig.() -> Unit = {}): HttpClient =
    createWith(::createDefaultHttpClient, block)

/** The built-in client (netonstream on native targets). */
internal expect fun createDefaultHttpClient(config: HttpClientConfig): HttpClient
