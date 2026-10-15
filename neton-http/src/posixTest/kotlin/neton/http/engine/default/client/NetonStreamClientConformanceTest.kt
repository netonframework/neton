package neton.http.engine.default.client

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.create
import neton.http.conformance.HttpClientConformanceSuite
import neton.http.engine.default.clientTls
import neton.http.engine.default.testIdentity
import kotlin.test.Test

/**
 * The shared client conformance suite against the built-in client, through the same
 * `HttpClient.create { }` an application calls. HTTP/2 is checked against a TLS origin that offers
 * `h2` by ALPN (the suite's scripted origin speaks HTTP/1.1 only).
 */
class NetonStreamClientConformanceTest : HttpClientConformanceSuite() {

    override fun createClient(block: HttpClientConfig.() -> Unit): HttpClient = HttpClient.create(block)

    override fun recordSkipped(capability: HttpClientCapability, testName: String) {
        println("conformance: skipped $testName, the built-in client does not declare $capability")
    }

    override suspend fun checkHttp2IsNegotiatedWhenOriginOffersIt(): Unit =
        requiring(HttpClientCapability.HTTP_2, "http2_is_negotiated_when_origin_offers_it") {
            val identity = testIdentity()
            val origin = TestOrigin.start(identity) { 200 to "over-h2".encodeToByteArray() }
            val client = HttpClient.createWith({ NetonStreamHttpClient(it, clientTls(identity, listOf("h2", "http/1.1"))) })
            try {
                val response = withTimeout(20_000) {
                    client.request(
                        HttpClientRequest(
                            method = HttpClientMethod.Get,
                            url = "https://127.0.0.1:${origin.port}/h2",
                            // The caller's own Host must not reach an HTTP/2 peer (strict servers answer 400).
                            headers = HttpHeaders.of("Host" to "spoofed.example"),
                        ),
                    )
                }
                check(response.statusCode == 200 && response.body == "over-h2") { "h2 response ${response.statusCode} '${response.body}'" }
                val seen = origin.seen.receive()
                check(seen.version == "HTTP/2.0") { "origin saw ${seen.version}, expected HTTP/2.0" }
                check(seen.authority == "127.0.0.1:${origin.port}") { ":authority was ${seen.authority}" }
                check(seen.all("host").isEmpty()) { "an HTTP/2 request carried a host field: ${seen.all("host")}" }
            } finally {
                client.close()
                origin.stop()
            }
        }

    @Test fun getReturnsStatusHeadersAndBody() = runBlocking { checkGetReturnsStatusHeadersAndBody() }
    @Test fun requestBodyBytesAreVerbatim() = runBlocking { checkRequestBodyBytesAreVerbatim() }
    @Test fun headersPreserveMultiValueAndCaseInsensitiveLookup() = runBlocking { checkHeadersPreserveMultiValueAndCaseInsensitiveLookup() }
    @Test fun requestReturnsNonSuccessStatusAsAResponse() = runBlocking { checkRequestReturnsNonSuccessStatusAsAResponse() }
    @Test fun streamThrowsHttpErrorForNonSuccessStatus() = runBlocking { checkStreamThrowsHttpErrorForNonSuccessStatus() }
    @Test fun connectionRefusedMapsToNetwork() = runBlocking { checkConnectionRefusedMapsToNetwork() }
    @Test fun requestTimeoutMapsToTimeout() = runBlocking { checkRequestTimeoutMapsToTimeout() }
    @Test fun closeIsIdempotentAndRejectsFurtherRequests() = runBlocking { checkCloseIsIdempotentAndRejectsFurtherRequests() }
    @Test fun streamingChunksEmitBeforeBodyCompletes() = runBlocking { checkStreamingChunksEmitBeforeBodyCompletes() }
    @Test fun flowCancellationClosesTheConnection() = runBlocking { checkFlowCancellationClosesTheConnection() }
    @Test fun proxyUrlRoutesThroughTheProxy() = runBlocking { checkProxyUrlRoutesThroughTheProxy() }
    @Test fun http2IsNegotiatedWhenOriginOffersIt() = runBlocking { checkHttp2IsNegotiatedWhenOriginOffersIt() }
    @Test fun customCaIsTrustedAndSystemCaIsNot() = runBlocking { checkCustomCaIsTrustedAndSystemCaIsNot() }
}
