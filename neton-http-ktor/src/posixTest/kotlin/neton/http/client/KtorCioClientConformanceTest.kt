package neton.http.client

import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import neton.http.conformance.HttpClientConformanceSuite
import kotlin.test.Test

/**
 * The same suite with the CIO engine pinned. CIO is what Linux uses; running it
 * on macOS too separates "the Ktor client is wrong" from "this platform's engine
 * is wrong", which the default-engine run cannot do on its own.
 */
class KtorCioClientConformanceTest : HttpClientConformanceSuite() {

    override fun createClient(block: HttpClientConfig.() -> Unit): HttpClient =
        HttpClient.createWithEngine(CIO, block)

    override fun recordSkipped(capability: HttpClientCapability, testName: String) {
        println("conformance(cio): skipped $testName, the Ktor client does not declare $capability")
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
