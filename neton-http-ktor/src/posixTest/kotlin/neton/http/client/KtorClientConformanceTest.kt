package neton.http.client

import kotlinx.coroutines.runBlocking
import neton.http.conformance.HttpClientConformanceSuite
import kotlin.test.Test

/**
 * Runs the shared client conformance suite against the Ktor client
 * (spec zh-hans/spec/http-engine.md §6). POSIX targets only: the suite's
 * origin talks BSD sockets.
 */
class KtorClientConformanceTest : HttpClientConformanceSuite() {

    override fun createClient(block: HttpClientConfig.() -> Unit): HttpClient =
        HttpClient.create(block)

    override fun recordSkipped(capability: HttpClientCapability, testName: String) {
        println("conformance: skipped $testName, the Ktor client does not declare $capability")
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
