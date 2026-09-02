package neton.storage.s3

import kotlinx.coroutines.flow.collect
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.http.client.HttpClientResponse
import neton.http.client.HttpClientStreamChunk
import neton.logging.Logger
import neton.storage.*
import neton.storage.internal.guessMimeType
import neton.storage.internal.ManagedStorageOperator
import kotlin.time.Duration

/**
 * S3 over Neton's own [HttpClient] (spec zh-hans/spec/http-engine.md rule 4):
 * the framework has exactly one outbound HTTP path, and this module used to be
 * the one place that bypassed it with a direct Ktor client.
 *
 * The client is borrowed from the application, which created it and closes it;
 * [close] therefore does not touch it. Closing a borrowed client would tear
 * down every other user of it, silently, at storage shutdown.
 */
internal class S3StorageOperator(
    override val name: String,
    private val endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val pathStyle: Boolean,
    private val httpClient: HttpClient,
    private val logger: Logger?
) : StorageOperator, ManagedStorageOperator {

    override val scheme: String = "s3"

    override fun close() {
        // Borrowed client: the application owns its lifecycle.
    }

    override suspend fun write(path: String, data: ByteArray, options: WriteOptions) {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val contentType = options.contentType ?: guessMimeType(path) ?: "application/octet-stream"

        val reqHeaders = mutableMapOf("Host" to host, "Content-Type" to contentType)
        if (!options.overwrite) {
            reqHeaders["If-None-Match"] = "*"
        }

        val signedHeaders = AwsV4Signer.sign("PUT", url, reqHeaders, data, accessKey, secretKey, region)

        val response = send(
            HttpClientMethod.Put, url, reqHeaders + signedHeaders,
            body = HttpClientBody.Bytes(data, contentType),
        )

        when (response.statusCode) {
            in 200..299 -> { /* success */
            }

            412 -> throw StorageAlreadyExistsException(path)
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 PUT failed: ${response.statusCode} for $path")
        }
    }

    override suspend fun read(path: String): ByteArray {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("GET", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        // Objects are bytes. `request()` hands back a String body, which would
        // corrupt anything that is not UTF-8 text, so the object body is read
        // through the streaming path and assembled here.
        val request = HttpClientRequest(
            method = HttpClientMethod.Get,
            url = url,
            headers = toHeaders(reqHeaders + signedHeaders),
        )
        var bytes = ByteArray(0)
        try {
            httpClient.stream(request).collect { chunk ->
                when (chunk) {
                    is HttpClientStreamChunk.Bytes -> bytes += chunk.bytes
                    is HttpClientStreamChunk.Text -> bytes += chunk.text.encodeToByteArray()
                    is HttpClientStreamChunk.End -> Unit
                }
            }
        } catch (e: HttpClientException) {
            val http = e.error as? HttpClientError.Http ?: throw StorageException("S3 GET failed for $path: ${e.error.message}", e)
            when (http.statusCode) {
                404 -> throw StorageNotFoundException(path)
                403 -> throw StorageAccessDeniedException(path)
                else -> throw StorageException("S3 GET failed: ${http.statusCode} for $path")
            }
        }
        return bytes
    }

    override suspend fun delete(path: String) {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("DELETE", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = send(HttpClientMethod.Delete, url, reqHeaders + signedHeaders)

        when (response.statusCode) {
            in 200..299 -> { /* success */
            }

            404 -> { /* idempotent delete */
            }

            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 DELETE failed: ${response.statusCode} for $path")
        }
    }

    override suspend fun exists(path: String): Boolean {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("HEAD", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = send(HttpClientMethod.Head, url, reqHeaders + signedHeaders)

        return when (response.statusCode) {
            in 200..299 -> true
            404 -> false
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 HEAD failed: ${response.statusCode} for $path")
        }
    }

    override suspend fun stat(path: String): FileStat {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("HEAD", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = send(HttpClientMethod.Head, url, reqHeaders + signedHeaders)

        return when (response.statusCode) {
            in 200..299 -> {
                val size = response.headers.get("Content-Length")?.toLongOrNull() ?: 0
                val contentType = response.headers.get("Content-Type")
                val lastModified = response.headers.get("Last-Modified")?.let { parseHttpDate(it) } ?: 0
                FileStat(
                    path = path,
                    size = size,
                    lastModified = lastModified,
                    isDirectory = false,
                    contentType = contentType
                )
            }

            404 -> throw StorageNotFoundException(path)
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 HEAD failed: ${response.statusCode} for $path")
        }
    }

    override suspend fun list(path: String, options: ListOptions): List<FileEntry> {
        val results = mutableListOf<FileEntry>()
        var continuationToken: String? = null

        do {
            val baseUrl = S3Utils.buildS3BaseUrl(endpoint, bucket, pathStyle)
            val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

            val queryParams = mutableListOf<String>()
            queryParams.add("list-type=2")
            if (path.isNotEmpty()) {
                val prefix = if (path.endsWith("/")) path else "$path/"
                queryParams.add("prefix=${AwsV4Signer.percentEncodeValue(prefix)}")
            }
            if (!options.recursive) {
                queryParams.add("delimiter=%2F")
            }
            queryParams.add("max-keys=${options.maxResults}")
            if (continuationToken != null) {
                queryParams.add("continuation-token=${AwsV4Signer.percentEncodeValue(continuationToken)}")
            }

            val url = "$baseUrl?${queryParams.joinToString("&")}"

            val reqHeaders = mapOf("Host" to host)
            val signedHeaders = AwsV4Signer.sign("GET", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

            val response = send(HttpClientMethod.Get, url, reqHeaders + signedHeaders)

            when (response.statusCode) {
                in 200..299 -> {
                    val parsed = S3Utils.parseListObjectsV2Response(response.body)

                    for (obj in parsed.contents) {
                        results.add(
                            FileEntry(
                                path = obj.key,
                                size = obj.size,
                                lastModified = S3Utils.parseIso8601ToEpochMillis(obj.lastModified),
                                isDirectory = false
                            )
                        )
                    }

                    for (prefix in parsed.commonPrefixes) {
                        results.add(
                            FileEntry(
                                path = prefix,
                                size = 0,
                                lastModified = 0,
                                isDirectory = true
                            )
                        )
                    }

                    continuationToken = if (parsed.isTruncated) parsed.nextContinuationToken else null
                }

                403 -> throw StorageAccessDeniedException(path)
                else -> throw StorageException("S3 LIST failed: ${response.statusCode} for $path")
            }
        } while (continuationToken != null && results.size < options.maxResults)

        return results.take(options.maxResults)
    }

    override suspend fun copy(src: String, dst: String) {
        val url = S3Utils.buildS3Url(endpoint, bucket, dst, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val encodedSrc = "/$bucket/${AwsV4Signer.percentEncodePath(src)}"
        val reqHeaders = mutableMapOf(
            "Host" to host,
            "x-amz-copy-source" to encodedSrc
        )

        val signedHeaders = AwsV4Signer.sign("PUT", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = send(HttpClientMethod.Put, url, reqHeaders + signedHeaders)

        when (response.statusCode) {
            in 200..299 -> { /* success */
            }

            404 -> throw StorageNotFoundException(src)
            403 -> throw StorageAccessDeniedException(src)
            else -> throw StorageException("S3 COPY failed: ${response.statusCode} for $src -> $dst")
        }
    }

    override suspend fun move(src: String, dst: String) {
        copy(src, dst)
        delete(src)
    }

    override suspend fun presignRead(path: String, ttl: Duration): String {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)
        return AwsV4Signer.presign("GET", url, mapOf("Host" to host), accessKey, secretKey, region, ttl)
    }

    override suspend fun presignWrite(path: String, ttl: Duration): String {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)
        return AwsV4Signer.presign("PUT", url, mapOf("Host" to host), accessKey, secretKey, region, ttl)
    }

    /** Transport failures become [StorageException]; HTTP statuses are the caller's to interpret. */
    private suspend fun send(
        method: HttpClientMethod,
        url: String,
        headers: Map<String, String>,
        body: HttpClientBody? = null,
    ): HttpClientResponse = try {
        httpClient.request(HttpClientRequest(method = method, url = url, headers = toHeaders(headers), body = body))
    } catch (e: HttpClientException) {
        throw StorageException("S3 ${method.name.uppercase()} failed for $url: ${e.error.message}", e)
    }

    private fun toHeaders(map: Map<String, String>): HttpHeaders = HttpHeaders.from(map)

    /**
     * Simple HTTP date parser (e.g., "Fri, 14 Feb 2026 09:30:00 GMT").
     * Falls back to 0 on parse failure.
     */
    private fun parseHttpDate(dateStr: String): Long {
        return try {
            // Format: "Day, DD Mon YYYY HH:MM:SS GMT"
            val parts = dateStr.split(" ")
            if (parts.size < 5) return 0
            val day = parts[1].toIntOrNull() ?: return 0
            val month = MONTH_MAP[parts[2].lowercase()] ?: return 0
            val year = parts[3].toIntOrNull() ?: return 0
            val timeParts = parts[4].split(":")
            val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
            val second = timeParts.getOrNull(2)?.toIntOrNull() ?: 0

            val iso = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${
                day.toString().padStart(2, '0')
            }T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${
                second.toString().padStart(2, '0')
            }Z"
            S3Utils.parseIso8601ToEpochMillis(iso)
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        private val MONTH_MAP = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4,
            "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
            "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
        )
    }
}
