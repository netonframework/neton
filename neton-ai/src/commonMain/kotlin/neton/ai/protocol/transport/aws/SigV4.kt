package neton.ai.protocol.transport.aws

import neton.security.crypto.HmacSha256
import neton.security.digest.Sha256

/**
 * AWS Signature Version 4（只做 Bedrock 用得到的子集：单次请求签名、header 鉴权、可选 session token）。
 * 规范见 docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html；
 * 非 S3 服务的 canonical URI 要对路径段做两次 URI 编码（模型 id 里的冒号：`%3A` → `%253A`）。
 */
@neton.ai.ExperimentalNetonAiApi
object SigV4 {
    data class Credentials(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null)

    data class Signed(val headers: Map<String, String>)

    /**
     * @param url 完整地址（path 已按单次编码，query 可空）
     * @param headers 参与签名的额外头（Content-Type 等），Host / X-Amz-Date 由本函数补
     */
    fun sign(
        creds: Credentials, region: String, service: String, method: String, url: String,
        headers: Map<String, String>, payload: ByteArray, nowMs: Long, includeContentSha: Boolean = false,
    ): Signed {
        val (host, path, query) = splitUrl(url)
        val amzDate = amzDate(nowMs)
        val dateStamp = amzDate.substring(0, 8)
        val allHeaders = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> allHeaders[k.lowercase()] = v.trim().replace(Regex("\\s+"), " ") }
        allHeaders["host"] = host
        allHeaders["x-amz-date"] = amzDate
        creds.sessionToken?.let { allHeaders["x-amz-security-token"] = it }
        val payloadHash = Sha256.hex(payload)
        if (includeContentSha) allHeaders["x-amz-content-sha256"] = payloadHash  // S3 才强制；Bedrock 不带也合法
        val signedNames = allHeaders.keys.sorted()
        val canonicalHeaders = signedNames.joinToString("") { "$it:${allHeaders[it]}\n" }
        val signedHeaders = signedNames.joinToString(";")
        val canonicalRequest = listOf(
            method.uppercase(), canonicalUri(path), canonicalQuery(query), canonicalHeaders, signedHeaders, payloadHash,
        ).joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, Sha256.hex(canonicalRequest)).joinToString("\n")
        val kDate = HmacSha256.sign("AWS4${creds.secretAccessKey}".encodeToByteArray(), dateStamp.encodeToByteArray())
        val kRegion = HmacSha256.sign(kDate, region.encodeToByteArray())
        val kService = HmacSha256.sign(kRegion, service.encodeToByteArray())
        val kSigning = HmacSha256.sign(kService, "aws4_request".encodeToByteArray())
        val signature = HmacSha256.signHex(kSigning, stringToSign.encodeToByteArray())
        val authorization = "AWS4-HMAC-SHA256 Credential=${creds.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> out[k] = v }
        out["X-Amz-Date"] = amzDate
        if (includeContentSha) out["X-Amz-Content-Sha256"] = payloadHash
        creds.sessionToken?.let { out["X-Amz-Security-Token"] = it }
        out["Authorization"] = authorization
        return Signed(out)
    }

    /** "AKIA...:secret[:sessionToken]" —— 渠道 Key 就存这一串。 */
    fun parseCredentials(apiKey: String): Credentials? {
        val parts = apiKey.trim().split(':')
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return Credentials(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /** bedrock-runtime.us-east-1.amazonaws.com → us-east-1 */
    fun regionFromHost(host: String): String? =
        Regex("^[a-z0-9-]+\\.([a-z]{2}-[a-z]+-\\d)\\.amazonaws\\.com(\\.cn)?$").find(host.lowercase())?.groupValues?.get(1)

    internal fun canonicalUri(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        return path.split('/').joinToString("/") { uriEncode(it, encodeSlash = true) }
    }

    internal fun canonicalQuery(query: String): String {
        if (query.isEmpty()) return ""
        return query.split('&').filter { it.isNotEmpty() }.map { kv ->
            val i = kv.indexOf('=')
            val k = if (i < 0) kv else kv.substring(0, i)
            val v = if (i < 0) "" else kv.substring(i + 1)
            uriEncode(percentDecode(k), true) to uriEncode(percentDecode(v), true)
        }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
    }

    /** RFC 3986 unreserved 之外全部 %XX（大写）。 */
    fun uriEncode(s: String, encodeSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            val keep = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '~' || (ch == '/' && !encodeSlash)
            if (keep) sb.append(ch) else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out += h.toByte(); i += 3; continue }
            }
            for (b in c.toString().encodeToByteArray()) out += b
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private fun splitUrl(url: String): Triple<String, String, String> {
        val noScheme = url.substringAfter("://")
        val hostEnd = noScheme.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) noScheme.length else it }
        val host = noScheme.substring(0, hostEnd)
        val rest = noScheme.substring(hostEnd)
        val q = rest.indexOf('?')
        val path = if (q < 0) rest else rest.substring(0, q)
        val query = if (q < 0) "" else rest.substring(q + 1)
        return Triple(host, path.ifEmpty { "/" }, query)
    }

    /** epoch ms → yyyyMMdd'T'HHmmss'Z'（UTC，civil-from-days，不依赖 datetime 库） */
    fun amzDate(nowMs: Long): String {
        val secs = nowMs / 1000
        var days = secs / 86400
        var rem = (secs % 86400).toInt()
        if (rem < 0) { rem += 86400; days -= 1 }
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        fun p2(n: Long) = n.toString().padStart(2, '0')
        return "${y.toString().padStart(4, '0')}${p2(m)}${p2(d)}T${p2((rem / 3600).toLong())}${p2(((rem % 3600) / 60).toLong())}${p2((rem % 60).toLong())}Z"
    }

    private const val HEX = "0123456789ABCDEF"
}
