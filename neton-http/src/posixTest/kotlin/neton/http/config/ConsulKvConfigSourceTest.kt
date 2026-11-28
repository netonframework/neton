package neton.http.config

import kotlinx.coroutines.runBlocking
import neton.core.config.getEnv
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.create
import neton.openssl.OpenSsl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsulKvConfigSourceTest {
    /** Explicit opt-in: this writes fixtures to a disposable local Consul agent. */
    @Test
    fun readsCommittedGroupAndRejectsInvalidReleases() = runBlocking {
        val endpoint = getEnv("NETON_TEST_DISPOSABLE_CONSUL") ?: return@runBlocking
        require(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(endpoint))
        val client = HttpClient.create {}
        val namespace = "neton/config/adapter-test/test"
        val base = "message = \"first\"\n"
        val env = "message = \"环境\"\n"
        fun hash(value: String) = OpenSsl.sha256(value.encodeToByteArray()).toHexString()
        suspend fun put(key: String, value: String) {
            val response = client.request(HttpClientRequest(
                HttpClientMethod.Put, "$endpoint/v1/kv/$namespace/$key",
                body = HttpClientBody.Text(value, "application/octet-stream"),
            ))
            assertEquals("true", response.body.trim())
        }
        try {
            val manifest = """{"test.conf":"${hash(base)}","test.test.conf":"${hash(env)}"}"""
            val names = setOf("test.conf", "test.test.conf")
            val source = ConsulKvConfigSource(endpoint, "adapter-test", "test", names, client = client)
            put("releases/A/manifest", manifest)
            put("releases/A/files/test.conf", base)
            put("releases/A/files/test.test.conf", env)
            put("current?cas=0", "A")
            assertEquals("A", source.currentRelease())
            assertEquals(mapOf("test.conf" to base, "test.test.conf" to env), source.readRelease("A"))

            put("releases/B/manifest", manifest)
            put("releases/B/files/test.conf", base)
            assertFailsWith<IllegalStateException> { source.readRelease("B") }
            put("releases/B/files/test.test.conf", base)
            assertFailsWith<IllegalStateException> { source.readRelease("B") }

            val bounded = ConsulKvConfigSource(endpoint, "adapter-test", "test", names, maxGroupBytes = 4, client = client)
            assertFailsWith<IllegalStateException> { bounded.readRelease("A") }
            assertTrue(source.readRelease("A").isNotEmpty())
        } finally {
            client.close()
        }
    }
}
