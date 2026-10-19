package neton.http.engine.default.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neton.http.client.HostResolver
import neton.http.client.HttpClient
import neton.http.client.HttpClientConfig
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.IpAddress
import neton.http.client.IpNetwork
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection
import neton.http.engine.default.TestIdentity
import neton.http.engine.default.clientTls
import neton.http.engine.default.testIdentity
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The built-in client with an [OutboundAddressPolicy]: the target is resolved once per connection, every
 * answer is checked, and the socket goes to exactly the checked address.
 */
@OptIn(ExperimentalAtomicApi::class)
class NetonStreamAddressPolicyTest {

    private val identity: TestIdentity by lazy { testIdentity() }

    /** The loopback stands in for "a public address" in these tests: it is the only one a test origin has. */
    private fun loopbackAsPublic(port: Int) = OutboundAddressPolicy(
        allowedPorts = setOf(port),
        allowedNetworks = listOf(IpNetwork.parse("127.0.0.1/32")),
    )

    private fun client(block: HttpClientConfig.() -> Unit): HttpClient =
        HttpClient.createWith({ NetonStreamHttpClient(it, clientTls(identity, listOf("http/1.1"))) }, block)

    private fun get(url: String) = HttpClientRequest(method = HttpClientMethod.Get, url = url)

    /** Answers from [answers] in turn (the last one repeats) and counts lookups. */
    private class ScriptedResolver(private vararg val answers: List<String>) : HostResolver {
        val lookups = AtomicInt(0)
        override suspend fun resolve(host: String): List<IpAddress> {
            val n = lookups.incrementAndFetch() - 1
            return answers[minOf(n, answers.lastIndex)].map { IpAddress.parse(it)!! }
        }
    }

    @Test
    fun defaultPolicyRefusesTheLoopbackBeforeAnyPacketIsSent() = runBlocking {
        val origin = TestOrigin.start(null)
        val client = client { addressPolicy = OutboundAddressPolicy(allowedPorts = setOf(origin.port)) }
        try {
            for (url in listOf(
                "http://127.0.0.1:${origin.port}/",
                "http://[::1]:${origin.port}/",
                "http://[::ffff:127.0.0.1]:${origin.port}/",
                "http://2130706433:${origin.port}/",
                "http://0x7f.1:${origin.port}/",
                "http://127.1:${origin.port}/",
            )) {
                val error = expectFailure { client.request(get(url)) }
                assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.ADDRESS, "$url: $error")
            }
            val error = expectFailure { client.stream(get("http://127.0.0.1:${origin.port}/s")).toList() }
            assertTrue(error is HttpClientError.TargetRejected, "stream: $error")
            assertTrue(origin.seen.tryReceive().isFailure, "the origin must not have seen any request")
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun aNameResolvingToTheLoopbackIsRefused() = runBlocking {
        val origin = TestOrigin.start(null)
        val resolver = ScriptedResolver(listOf("127.0.0.1"))
        val client = client {
            addressPolicy = OutboundAddressPolicy(allowedPorts = setOf(origin.port))
            hostResolver = resolver
        }
        try {
            val error = expectFailure { client.request(get("http://alias.example:${origin.port}/")) }
            assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.ADDRESS, "got $error")
            assertTrue(origin.seen.tryReceive().isFailure)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun portOutsideThePolicyIsRefused() = runBlocking {
        val client = client { addressPolicy = OutboundAddressPolicy() }
        try {
            val error = expectFailure { client.request(get("http://93.184.216.34:8080/")) }
            assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.PORT, "got $error")
            val scheme = expectFailure { client.request(get("ftp://93.184.216.34/")) }
            assertTrue(scheme is HttpClientError.TargetRejected && scheme.reason == TargetRejection.SCHEME, "got $scheme")
        } finally {
            client.close()
        }
    }

    @Test
    fun unresolvableNameIsAResolveRejection() = runBlocking {
        val client = client {
            addressPolicy = OutboundAddressPolicy()
            hostResolver = HostResolver { throw IllegalStateException("NXDOMAIN") }
        }
        try {
            val error = expectFailure { client.request(get("https://nx.example/")) }
            assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.RESOLVE, "got $error")
        } finally {
            client.close()
        }
    }

    @Test
    fun rebindingCannotMoveAnAdmittedConnection() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val origin = TestOrigin.start(null) { seen ->
            if (seen.path == "/slow") release.await()
            200 to seen.path.encodeToByteArray()
        }
        // First lookup: the admitted address. Every later lookup: an internal one.
        val resolver = ScriptedResolver(listOf("127.0.0.1"), listOf("10.0.0.1"))
        val client = client {
            addressPolicy = loopbackAsPublic(origin.port)
            hostResolver = resolver
        }
        try {
            val base = "http://rebind.example:${origin.port}"
            val slow = async { client.request(get("$base/slow")) }
            val seen = withTimeout(20_000) { origin.seen.receive() }
            // The connection went to the checked address, resolved once.
            assertEquals("/slow", seen.path)
            assertEquals(1, resolver.lookups.load())
            assertEquals("rebind.example:${origin.port}", seen.all("host").single())
            // A second connection (the first is busy) resolves again, gets the internal answer, and is refused
            // instead of being dialled.
            val error = expectFailure { client.request(get("$base/second")) }
            assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.ADDRESS, "got $error")
            assertEquals(2, resolver.lookups.load())
            release.complete(Unit)
            assertEquals("/slow", slow.await().body)
        } finally {
            release.complete(Unit)
            client.close()
            origin.stop()
        }
    }

    @Test
    fun mixedAnswerWithOneInternalAddressIsRefused() = runBlocking {
        val origin = TestOrigin.start(null)
        val client = client {
            addressPolicy = loopbackAsPublic(origin.port)
            hostResolver = ScriptedResolver(listOf("127.0.0.1", "169.254.169.254"))
        }
        try {
            val error = expectFailure { client.request(get("http://mixed.example:${origin.port}/")) }
            assertTrue(error is HttpClientError.TargetRejected && error.reason == TargetRejection.ADDRESS, "got $error")
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun tlsVerifiesAndSendsSniForTheHostNameWhileDiallingThePinnedAddress() = runBlocking {
        // The test certificate names DNS:localhost; the connection goes to the resolved 127.0.0.1 literal.
        val origin = TestOrigin.start(identity, alpn = listOf("http/1.1"))
        val resolver = ScriptedResolver(listOf("127.0.0.1"))
        val client = client {
            addressPolicy = loopbackAsPublic(origin.port)
            hostResolver = resolver
        }
        try {
            val response = withTimeout(20_000) { client.request(get("https://localhost:${origin.port}/tls")) }
            assertEquals(200, response.statusCode)
            assertEquals(listOf("localhost:${origin.port}"), origin.seen.receive().all("host"))
            assertEquals(1, resolver.lookups.load())
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun allowlistedHostReachesALocalStub() = runBlocking {
        val origin = TestOrigin.start(null) { 200 to "stub".encodeToByteArray() }
        val client = client {
            addressPolicy = OutboundAddressPolicy(allowedHosts = setOf("stub.local.test"))
            hostResolver = ScriptedResolver(listOf("127.0.0.1"))
        }
        try {
            assertEquals("stub", withTimeout(20_000) { client.request(get("http://stub.local.test:${origin.port}/")) }.body)
        } finally {
            client.close()
            origin.stop()
        }
    }

    @Test
    fun policyAndProxyCannotBeCombined() {
        try {
            client {
                addressPolicy = OutboundAddressPolicy()
                proxyUrl = "http://127.0.0.1:7890"
            }
            fail("expected the config to be refused")
        } catch (e: HttpClientException) {
            assertTrue("addressPolicy" in e.error.message, e.error.message)
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): HttpClientError {
        try {
            withTimeout(20_000) { block() }
        } catch (e: HttpClientException) {
            return e.error
        }
        fail("expected HttpClientException")
    }
}
