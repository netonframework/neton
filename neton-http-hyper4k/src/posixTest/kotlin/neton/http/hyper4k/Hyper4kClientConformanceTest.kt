package neton.http.hyper4k

import kotlinx.coroutines.runBlocking
import neton.http.client.HttpClient
import neton.http.client.HttpClientCapability
import neton.http.client.HttpClientConfig
import neton.http.client.create
import neton.http.conformance.HttpClientConformanceSuite
import kotlin.test.Test

/**
 * The shared client conformance suite against the hyper4k client, through the
 * same `HttpClient.create { }` an application would call.
 */
class Hyper4kClientConformanceTest : HttpClientConformanceSuite() {

    override fun createClient(block: HttpClientConfig.() -> Unit): HttpClient =
        HttpClient.create(block)

    override fun recordSkipped(capability: HttpClientCapability, testName: String) {
        println("conformance: skipped $testName, the hyper4k client does not declare $capability")
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
