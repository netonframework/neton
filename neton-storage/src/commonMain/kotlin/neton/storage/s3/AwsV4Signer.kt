package neton.storage.s3

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.time.Duration

/**
 * AWS Signature V4 signing (pure Kotlin, using cryptography-kotlin).
 */
internal object AwsV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    /**
     * Sign a request, returning headers to attach (Authorization, x-amz-date, x-amz-content-sha256, host).
     */
    fun sign(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: ByteArray,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String = "s3"
    ): Map<String, String> {
        val now = currentTimeMillis()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)

        val hashedPayload = hexEncode(sha256(payload))

        val allHeaders = mutableMapOf<String, String>()
        allHeaders.putAll(headers)
        allHeaders["x-amz-date"] = amzDate
        allHeaders["x-amz-content-sha256"] = hashedPayload

        val (canonicalHeaders, signedHeaders) = buildCanonicalHeaders(allHeaders)
        val parsed = parseUrl(url)
        val canonicalUri = percentEncodePath(parsed.path)
        val canonicalQueryString = buildCanonicalQueryString(parsed.query)

        val canonicalRequest =
            "$method\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${hexEncode(sha256(canonicalRequest.encodeToByteArray()))}"

        val signingKey = buildSigningKey(secretKey, dateStamp, region, service)
        val signature = hexEncode(hmacSha256(signingKey, stringToSign.encodeToByteArray()))

        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        val result = mutableMapOf<String, String>()
        result["Authorization"] = authorization
        result["x-amz-date"] = amzDate
        result["x-amz-content-sha256"] = hashedPayload
        return result
    }

    /**
     * Generate a presigned URL (query string signing).
     */
    fun presign(
        method: String,
        url: String,
        headers: Map<String, String>,
        accessKey: String,
        secretKey: String,
        region: String,
        ttl: Duration,
        service: String = "s3"
    ): String {
        val now = currentTimeMillis()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/$region/$service/aws4_request"
        val ttlSeconds = ttl.inWholeSeconds

        val parsed = parseUrl(url)
        val canonicalUri = percentEncodePath(parsed.path)

        // Build host header for signing
        val hostHeader = headers["Host"] ?: headers["host"] ?: parsed.host

        // Presign query params
        val presignParams = mutableMapOf<String, String>()
        presignParams["X-Amz-Algorithm"] = ALGORITHM
        presignParams["X-Amz-Credential"] = "$accessKey/$scope"
        presignParams["X-Amz-Date"] = amzDate
        presignParams["X-Amz-Expires"] = ttlSeconds.toString()
        presignParams["X-Amz-SignedHeaders"] = "host"

        // Merge existing query params
        if (parsed.query.isNotEmpty()) {
            for (param in parsed.query.split("&")) {
                val eq = param.indexOf('=')
                if (eq > 0) {
                    presignParams[param.substring(0, eq)] = param.substring(eq + 1)
                }
            }
        }

        val canonicalQueryString = presignParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${percentEncodeValue(it.key)}=${percentEncodeValue(it.value)}" }

        val canonicalHeaders = "host:${hostHeader.trim()}\n"
        val signedHeaders = "host"

        val canonicalRequest =
            "$method\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$UNSIGNED_PAYLOAD"

        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${hexEncode(sha256(canonicalRequest.encodeToByteArray()))}"
        val signingKey = buildSigningKey(secretKey, dateStamp, region, service)
        val signature = hexEncode(hmacSha256(signingKey, stringToSign.encodeToByteArray()))

        val finalQuery = "$canonicalQueryString&X-Amz-Signature=$signature"
        return "${parsed.scheme}://${parsed.host}$canonicalUri?$finalQuery"
    }

    // --- Crypto helpers ---

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val provider = CryptographyProvider.Default
        val hmac = provider.get(HMAC)
        val decoder = hmac.keyDecoder(SHA256)
        val hmacKey = decoder.decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        return hmacKey.signatureGenerator().generateSignatureBlocking(data)
    }

    internal fun sha256(data: ByteArray): ByteArray {
        val provider = CryptographyProvider.Default
        val digest = provider.get(SHA256)
        return digest.hasher().hashBlocking(data)
    }

    internal fun hexEncode(bytes: ByteArray): String {
        return bytes.joinToString("") { b ->
            val hex = (b.toInt() and 0xFF).toString(16)
            if (hex.length == 1) "0$hex" else hex
        }
    }

    private fun buildSigningKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256("AWS4$secretKey".encodeToByteArray(), dateStamp.encodeToByteArray())
        val kRegion = hmacSha256(kDate, region.encodeToByteArray())
        val kService = hmacSha256(kRegion, service.encodeToByteArray())
        return hmacSha256(kService, "aws4_request".encodeToByteArray())
    }

    private fun buildCanonicalHeaders(headers: Map<String, String>): Pair<String, String> {
        val sorted = headers.entries
            .map { (k, v) -> k.lowercase() to v.trim().replace(Regex("\\s+"), " ") }
            .sortedBy { it.first }
        val canonical = sorted.joinToString("") { "${it.first}:${it.second}\n" }
        val signed = sorted.joinToString(";") { it.first }
        return canonical to signed
    }

    private fun buildCanonicalQueryString(queryString: String): String {
        if (queryString.isEmpty()) return ""
        return queryString.split("&")
            .map { param ->
                val eq = param.indexOf('=')
                if (eq > 0) {
                    percentEncodeValue(param.substring(0, eq)) to percentEncodeValue(param.substring(eq + 1))
                } else {
                    percentEncodeValue(param) to ""
                }
            }
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    // RFC 3986 percent-encode for URI path — preserve '/'
    internal fun percentEncodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            percentEncodeValue(segment)
        }
    }

    // RFC 3986 percent-encode — encode everything except unreserved chars
    internal fun percentEncodeValue(value: String): String {
        val sb = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%')
                val hex = (byte.toInt() and 0xFF).toString(16).uppercase()
                if (hex.length == 1) sb.append('0')
                sb.append(hex)
            }
        }
        return sb.toString()
    }

    // --- URL parsing ---

    private data class ParsedUrl(val scheme: String, val host: String, val path: String, val query: String)

    private fun parseUrl(url: String): ParsedUrl {
        val schemeEnd = url.indexOf("://")
        val scheme = if (schemeEnd > 0) url.substring(0, schemeEnd) else "https"
        val rest = if (schemeEnd > 0) url.substring(schemeEnd + 3) else url

        val pathStart = rest.indexOf('/')
        val host: String
        val pathAndQuery: String
        if (pathStart >= 0) {
            host = rest.substring(0, pathStart)
            pathAndQuery = rest.substring(pathStart)
        } else {
            host = rest
            pathAndQuery = "/"
        }

        val queryStart = pathAndQuery.indexOf('?')
        val path: String
        val query: String
        if (queryStart >= 0) {
            path = pathAndQuery.substring(0, queryStart)
            query = pathAndQuery.substring(queryStart + 1)
        } else {
            path = pathAndQuery
            query = ""
        }

        return ParsedUrl(scheme, host, path, query)
    }

    // --- Time formatting ---

    internal fun formatAmzDate(epochMillis: Long): String {
        // Convert epoch millis to YYYYMMDD'T'HHMMSS'Z' in UTC
        val epochSeconds = epochMillis / 1000
        // Days since 1970-01-01
        var days = (epochSeconds / 86400).toInt()
        val timeOfDay = (epochSeconds % 86400).toInt()
        val hours = timeOfDay / 3600
        val minutes = (timeOfDay % 3600) / 60
        val seconds = timeOfDay % 60

        // Convert days to Y/M/D
        var year = 1970
        while (true) {
            val daysInYear = if (isLeapYear(year)) 366 else 365
            if (days < daysInYear) break
            days -= daysInYear
            year++
        }

        val monthDays = if (isLeapYear(year))
            intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        else
            intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        var month = 0
        while (month < 12 && days >= monthDays[month]) {
            days -= monthDays[month]
            month++
        }
        val day = days + 1
        month += 1

        return buildString {
            append(year.toString().padStart(4, '0'))
            append(month.toString().padStart(2, '0'))
            append(day.toString().padStart(2, '0'))
            append('T')
            append(hours.toString().padStart(2, '0'))
            append(minutes.toString().padStart(2, '0'))
            append(seconds.toString().padStart(2, '0'))
            append('Z')
        }
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

/**
 * Platform-specific current time in millis.
 */
internal expect fun currentTimeMillis(): Long
