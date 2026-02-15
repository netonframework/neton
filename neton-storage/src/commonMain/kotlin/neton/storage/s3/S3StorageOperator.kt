package neton.storage.s3

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import neton.logging.Logger
import neton.storage.*
import neton.storage.internal.guessMimeType
import kotlin.time.Duration

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
) : StorageOperator {

    override val scheme: String = "s3"

    override suspend fun write(path: String, data: ByteArray, options: WriteOptions) {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val contentType = options.contentType ?: guessMimeType(path) ?: "application/octet-stream"

        val reqHeaders = mutableMapOf("Host" to host, "Content-Type" to contentType)
        if (!options.overwrite) {
            reqHeaders["If-None-Match"] = "*"
        }

        val signedHeaders = AwsV4Signer.sign("PUT", url, reqHeaders, data, accessKey, secretKey, region)

        val response = httpClient.put(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
            setBody(data)
        }

        when (response.status.value) {
            in 200..299 -> { /* success */
            }

            412 -> throw StorageAlreadyExistsException(path)
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 PUT failed: ${response.status.value} for $path")
        }
    }

    override suspend fun read(path: String): ByteArray {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("GET", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = httpClient.get(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
        }

        return when (response.status.value) {
            in 200..299 -> response.readRawBytes()
            404 -> throw StorageNotFoundException(path)
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 GET failed: ${response.status.value} for $path")
        }
    }

    override suspend fun delete(path: String) {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("DELETE", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = httpClient.delete(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
        }

        when (response.status.value) {
            in 200..299 -> { /* success */
            }

            404 -> { /* idempotent delete */
            }

            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 DELETE failed: ${response.status.value} for $path")
        }
    }

    override suspend fun exists(path: String): Boolean {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("HEAD", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = httpClient.head(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
        }

        return when (response.status.value) {
            in 200..299 -> true
            404 -> false
            403 -> throw StorageAccessDeniedException(path)
            else -> throw StorageException("S3 HEAD failed: ${response.status.value} for $path")
        }
    }

    override suspend fun stat(path: String): FileStat {
        val url = S3Utils.buildS3Url(endpoint, bucket, path, pathStyle)
        val host = S3Utils.buildHostHeader(endpoint, bucket, pathStyle)

        val reqHeaders = mapOf("Host" to host)
        val signedHeaders = AwsV4Signer.sign("HEAD", url, reqHeaders, ByteArray(0), accessKey, secretKey, region)

        val response = httpClient.head(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
        }

        return when (response.status.value) {
            in 200..299 -> {
                val size = response.headers["Content-Length"]?.toLongOrNull() ?: 0
                val contentType = response.headers["Content-Type"]
                val lastModified = response.headers["Last-Modified"]?.let { parseHttpDate(it) } ?: 0
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
            else -> throw StorageException("S3 HEAD failed: ${response.status.value} for $path")
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

            val response = httpClient.get(url) {
                for ((k, v) in reqHeaders) header(k, v)
                for ((k, v) in signedHeaders) header(k, v)
            }

            when (response.status.value) {
                in 200..299 -> {
                    val xml = response.bodyAsText()
                    val parsed = S3Utils.parseListObjectsV2Response(xml)

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
                else -> throw StorageException("S3 LIST failed: ${response.status.value} for $path")
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

        val response = httpClient.put(url) {
            for ((k, v) in reqHeaders) header(k, v)
            for ((k, v) in signedHeaders) header(k, v)
        }

        when (response.status.value) {
            in 200..299 -> { /* success */
            }

            404 -> throw StorageNotFoundException(src)
            403 -> throw StorageAccessDeniedException(src)
            else -> throw StorageException("S3 COPY failed: ${response.status.value} for $src -> $dst")
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
