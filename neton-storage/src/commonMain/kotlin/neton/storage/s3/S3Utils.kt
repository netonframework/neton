package neton.storage.s3

/**
 * S3 URL construction and XML response parsing utilities.
 */
internal object S3Utils {

    data class EndpointParts(val scheme: String, val host: String, val port: Int)

    fun parseEndpoint(endpoint: String): EndpointParts {
        val schemeEnd = endpoint.indexOf("://")
        val scheme: String
        val rest: String
        if (schemeEnd > 0) {
            scheme = endpoint.substring(0, schemeEnd)
            rest = endpoint.substring(schemeEnd + 3)
        } else {
            scheme = "https"
            rest = endpoint
        }
        val colonPos = rest.indexOf(':')
        val host: String
        val port: Int
        if (colonPos > 0) {
            host = rest.substring(0, colonPos)
            port = rest.substring(colonPos + 1).trimEnd('/').toIntOrNull()
                ?: if (scheme == "https") 443 else 80
        } else {
            host = rest.trimEnd('/')
            port = if (scheme == "https") 443 else 80
        }
        return EndpointParts(scheme, host, port)
    }

    fun buildS3Url(endpoint: String, bucket: String, key: String, pathStyle: Boolean): String {
        val parts = parseEndpoint(endpoint)
        val portSuffix = portSuffix(parts)
        return if (pathStyle) {
            "${parts.scheme}://${parts.host}$portSuffix/$bucket/$key"
        } else {
            "${parts.scheme}://$bucket.${parts.host}$portSuffix/$key"
        }
    }

    fun buildS3BaseUrl(endpoint: String, bucket: String, pathStyle: Boolean): String {
        val parts = parseEndpoint(endpoint)
        val portSuffix = portSuffix(parts)
        return if (pathStyle) {
            "${parts.scheme}://${parts.host}$portSuffix/$bucket"
        } else {
            "${parts.scheme}://$bucket.${parts.host}$portSuffix"
        }
    }

    fun buildHostHeader(endpoint: String, bucket: String, pathStyle: Boolean): String {
        val parts = parseEndpoint(endpoint)
        val portSuffix = portSuffix(parts)
        return if (pathStyle) {
            "${parts.host}$portSuffix"
        } else {
            "$bucket.${parts.host}$portSuffix"
        }
    }

    private fun portSuffix(parts: EndpointParts): String {
        val defaultPort = if (parts.scheme == "https") 443 else 80
        return if (parts.port != defaultPort) ":${parts.port}" else ""
    }

    // --- XML parsing for ListObjectsV2 ---

    data class ListObjectsResult(
        val contents: List<S3Object>,
        val commonPrefixes: List<String>,
        val isTruncated: Boolean,
        val nextContinuationToken: String?
    )

    data class S3Object(
        val key: String,
        val size: Long,
        val lastModified: String
    )

    fun parseListObjectsV2Response(xml: String): ListObjectsResult {
        val contents = mutableListOf<S3Object>()
        val commonPrefixes = mutableListOf<String>()

        // Parse <Contents> blocks
        var searchFrom = 0
        while (true) {
            val start = xml.indexOf("<Contents>", searchFrom)
            if (start < 0) break
            val end = xml.indexOf("</Contents>", start)
            if (end < 0) break
            val block = xml.substring(start, end)

            val key = extractTag(block, "Key") ?: ""
            val size = extractTag(block, "Size")?.toLongOrNull() ?: 0
            val lastMod = extractTag(block, "LastModified") ?: ""
            contents.add(S3Object(key, size, lastMod))
            searchFrom = end + "</Contents>".length
        }

        // Parse <CommonPrefixes><Prefix>
        searchFrom = 0
        while (true) {
            val start = xml.indexOf("<CommonPrefixes>", searchFrom)
            if (start < 0) break
            val end = xml.indexOf("</CommonPrefixes>", start)
            if (end < 0) break
            val block = xml.substring(start, end)
            val prefix = extractTag(block, "Prefix")
            if (prefix != null) commonPrefixes.add(prefix)
            searchFrom = end + "</CommonPrefixes>".length
        }

        val isTruncated = extractTag(xml, "IsTruncated") == "true"
        val nextToken = extractTag(xml, "NextContinuationToken")

        return ListObjectsResult(contents, commonPrefixes, isTruncated, nextToken)
    }

    private fun extractTag(xml: String, tag: String): String? {
        val openTag = "<$tag>"
        val closeTag = "</$tag>"
        val start = xml.indexOf(openTag)
        if (start < 0) return null
        val valueStart = start + openTag.length
        val end = xml.indexOf(closeTag, valueStart)
        if (end < 0) return null
        return xml.substring(valueStart, end)
    }

    /**
     * Parse ISO 8601 datetime (e.g., "2026-02-14T09:30:00.000Z") to epoch millis.
     */
    fun parseIso8601ToEpochMillis(iso: String): Long {
        if (iso.isEmpty()) return 0

        val year = iso.substring(0, 4).toIntOrNull() ?: return 0
        val month = iso.substring(5, 7).toIntOrNull() ?: return 0
        val day = iso.substring(8, 10).toIntOrNull() ?: return 0
        val hour = if (iso.length > 11) iso.substring(11, 13).toIntOrNull() ?: 0 else 0
        val minute = if (iso.length > 14) iso.substring(14, 16).toIntOrNull() ?: 0 else 0
        val second = if (iso.length > 17) iso.substring(17, 19).toIntOrNull() ?: 0 else 0

        // Days from epoch to start of year
        var days = 0L
        for (y in 1970 until year) {
            days += if (isLeapYear(y)) 366 else 365
        }

        val monthDays = if (isLeapYear(year))
            intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        else
            intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        for (m in 0 until (month - 1)) {
            days += monthDays[m]
        }
        days += (day - 1)

        return days * 86400_000L + hour * 3600_000L + minute * 60_000L + second * 1000L
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}
