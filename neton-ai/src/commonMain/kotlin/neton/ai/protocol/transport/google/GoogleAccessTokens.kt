package neton.ai.protocol.transport.google

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import neton.core.http.HttpHeaders
import neton.http.client.HttpClient
import neton.http.client.HttpClientBody
import neton.http.client.HttpClientMethod
import neton.http.client.HttpClientRequest
import neton.security.crypto.RsaSha256

/**
 * Google OAuth2 access token（Vertex AI 鉴权，spec §3.2）。
 *
 * 凭据要么是服务账号 JSON（以 `{` 开头，含 client_email / private_key / token_uri），走 JWT bearer 换
 * access token，按 client_email 缓存到过期前 60 秒；要么直接就是一枚 access token（调试用，原样返回）。
 *
 * HTTP 客户端由宿主传入并负责关闭（neton-http 的所有权约定）。
 */
@neton.ai.ExperimentalNetonAiApi
class GoogleAccessTokens(
    private val client: HttpClient,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private data class Cached(val token: String, val expiresAtMs: Long)

    private val cache = HashMap<String, Cached>()
    private val mutex = Mutex()

    suspend fun bearer(credential: String): String {
        val trimmed = credential.trim()
        if (!trimmed.startsWith("{")) return trimmed
        val sa = JSON.parseToJsonElement(trimmed).jsonObject
        val email = sa.string("client_email") ?: throw IllegalArgumentException("service account JSON has no client_email")
        val nowMs = now()
        mutex.withLock { cache[email]?.takeIf { it.expiresAtMs - 60_000 > nowMs } }?.let { return it.token }
        val fresh = exchange(sa, email, nowMs)
        mutex.withLock { cache[email] = fresh }
        return fresh.token
    }

    private suspend fun exchange(sa: JsonObject, email: String, nowMs: Long): Cached {
        val tokenUri = sa.string("token_uri") ?: DEFAULT_TOKEN_URI
        val assertion = assertion(sa, email, tokenUri, nowMs)
        val res = client.request(HttpClientRequest(
            method = HttpClientMethod.Post,
            url = tokenUri,
            headers = HttpHeaders.from(mapOf("Content-Type" to FORM)),
            body = HttpClientBody.Text("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$assertion", FORM),
        ))
        if (res.statusCode !in 200..299) throw IllegalStateException("Google token exchange failed: HTTP ${res.statusCode}")
        val obj = JSON.parseToJsonElement(res.body).jsonObject
        val token = obj.string("access_token") ?: throw IllegalStateException("Google token exchange returned no access_token")
        val ttl = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        return Cached(token, nowMs + ttl * 1000)
    }

    companion object {
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val FORM = "application/x-www-form-urlencoded"
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        /** 签好的 JWT assertion（RS256，有效期 1 小时）。 */
        fun assertion(sa: JsonObject, email: String, tokenUri: String, nowMs: Long): String {
            val privateKey = sa.string("private_key") ?: throw IllegalArgumentException("service account JSON has no private_key")
            val iat = nowMs / 1000
            val header = b64url("""{"alg":"RS256","typ":"JWT"}""".encodeToByteArray())
            val claims = b64url(buildJsonObject {
                put("iss", email); put("scope", SCOPE); put("aud", tokenUri); put("iat", iat); put("exp", iat + 3600)
            }.toString().encodeToByteArray())
            val signingInput = "$header.$claims"
            return "$signingInput.${b64url(RsaSha256.sign(privateKey, signingInput.encodeToByteArray()))}"
        }

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        private fun b64url(b: ByteArray): String = kotlin.io.encoding.Base64.UrlSafe.encode(b).trimEnd('=')
    }
}
