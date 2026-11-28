package neton.http.config

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.core.config.ExternalConfigSource
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientStreamChunk
import neton.http.client.HttpClientTimeouts
import neton.openssl.OpenSsl

/**
 * Read-only Consul KV source. The caller owns the supplied client and its TLS trust.
 *
 * Layout under `neton/config/<service>/<environment>`: `current` contains the release ID;
 * `releases/<id>/manifest` is a JSON object mapping exact filenames to lowercase SHA-256;
 * `releases/<id>/files/<name>` contains each UTF-8 document. The publisher writes the
 * immutable group first and switches `current` using CAS.
 */
class ConsulKvConfigSource(
    endpoint: String,
    service: String,
    environment: String,
    files: Set<String>,
    token: String? = null,
    private val maxGroupBytes: Int = 2 * 1024 * 1024,
    private val client: HttpClient,
) : ExternalConfigSource {
    private val endpoint = endpoint.trimEnd('/')
    private val files = files.toSet()
    private val prefix = "neton/config/$service/$environment"
    private val headers = if (token == null) HttpHeaders.EMPTY else HttpHeaders.of("X-Consul-Token" to token)
    private val timeout = HttpClientTimeouts(connectMillis = 2_000, requestMillis = 5_000, socketMillis = 5_000)

    init {
        require(Regex("https?://(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+])(?::[0-9]{1,5})?").matches(this.endpoint)) {
            "Consul endpoint must be a trusted HTTP(S) origin"
        }
        require(SEGMENT.matches(service) && SEGMENT.matches(environment))
        require(this.files.isNotEmpty() && this.files.size <= 64 && this.files.all(FILE::matches))
        require(maxGroupBytes in 1..(16 * 1024 * 1024))
        require(token == null || this.endpoint.startsWith("https://") || LOOPBACK.matches(this.endpoint)) {
            "Consul credentials require HTTPS or a loopback agent"
        }
    }

    override suspend fun currentRelease(): String =
        checked { get("current", 128).decodeToString(throwOnInvalidSequence = true).also { require(SEGMENT.matches(it)) } }

    override suspend fun readRelease(release: String): Map<String, String> = checked {
        require(SEGMENT.matches(release))
        val manifest = Json.parseToJsonElement(
            get("releases/$release/manifest", 16_384).decodeToString(throwOnInvalidSequence = true),
        ) as JsonObject
        require(manifest.keys == files)
        var remaining = maxGroupBytes
        buildMap {
            for (name in files.sorted()) {
                val expected = manifest.getValue(name) as JsonPrimitive
                require(expected.isString && DIGEST.matches(expected.content))
                val bytes = get("releases/$release/files/$name", minOf(remaining, 512 * 1024))
                remaining -= bytes.size
                require(OpenSsl.sha256(bytes).toHexString() == expected.content)
                put(name, bytes.decodeToString(throwOnInvalidSequence = true))
            }
        }
    }

    private suspend fun get(key: String, limit: Int): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        var complete = false
        client.stream(HttpClientRequest(
            method = HttpClientMethod.Get,
            url = "$endpoint/v1/kv/$prefix/$key?raw&consistent",
            headers = headers,
            timeout = timeout,
        )).collect { chunk ->
            when (chunk) {
                is HttpClientStreamChunk.Start -> check(chunk.statusCode == 200)
                is HttpClientStreamChunk.Bytes -> {
                    require(chunk.bytes.size <= limit - size)
                    size += chunk.bytes.size
                    chunks += chunk.bytes
                }
                is HttpClientStreamChunk.Text -> error("Expected raw Consul KV bytes")
                is HttpClientStreamChunk.End -> complete = true
            }
        }
        check(complete)
        val result = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private suspend inline fun <T> checked(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The HTTP exception or parser error may contain config content or an upstream error body.
        throw IllegalStateException("Consul configuration read failed")
    }

    private companion object {
        val SEGMENT = Regex("[A-Za-z0-9_-]{1,96}")
        val FILE = Regex("[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9_-]{1,32})?\\.conf")
        val DIGEST = Regex("[0-9a-f]{64}")
        val LOOPBACK = Regex("http://(?:127\\.0\\.0\\.1|\\[::1])(?::[0-9]{1,5})?")
    }
}
