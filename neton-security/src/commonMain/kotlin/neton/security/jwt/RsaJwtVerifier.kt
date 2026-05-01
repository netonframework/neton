package neton.security.jwt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Clock

/**
 * 验签 + 校验 unified token claims（spec TOKEN_UNIFICATION_SPEC v1.3 §4 / §6.1）。
 *
 * **不**做：
 * - HTTP / JWKS 拉取（走 [JwksKeyProvider]）
 * - `session_version` DB 比对（走 [UnifiedTokenAuthenticator]）
 * - Identity 注入（走 [UnifiedTokenAuthenticator]）
 *
 * 错误是结构化的 [VerifyError] sealed enum；caller 拿到 [VerifyResult.Failure]
 * 必须 fail-closed（不能"软"放行）。
 */
public class RsaJwtVerifier(
    private val keyProvider: JwksKeyProvider,
    /** 期望的 token issuer；spec §4 默认 `"privchat-server"`。 */
    private val expectedIssuer: String = DEFAULT_ISSUER,
    /** token `aud` 数组中**必须**含此值；缺则视为 [VerifyError.AudienceMismatch]。 */
    private val requiredAudience: String = DEFAULT_REQUIRED_AUDIENCE,
    /** clock skew 容忍秒数；spec §11.3 推荐 5s。 */
    private val leewaySeconds: Long = DEFAULT_LEEWAY_SECONDS,
    private val clock: Clock = Clock.System,
) {
    /**
     * 期望 `typ` 为 [expectedTyp]（默认 `"access"`）。Phase A middleware 路径只接受
     * access token；refresh token 永远不应该出现在 `Authorization` header 里。
     */
    public suspend fun verify(
        token: String,
        expectedTyp: String = TYP_ACCESS,
    ): VerifyResult {
        // 1) 切三段
        val parts = token.split('.')
        if (parts.size != 3) return fail(VerifyError.Malformed)
        val (headerB64, payloadB64, signatureB64) = Triple(parts[0], parts[1], parts[2])

        // 2) 解 header
        val headerBytes = base64UrlDecodeOrNull(headerB64) ?: return fail(VerifyError.Malformed)
        val header = parseJsonObjectOrNull(headerBytes) ?: return fail(VerifyError.Malformed)
        val alg = header["alg"]?.asStringOrNull()
        if (alg != ALG_RS256) return fail(VerifyError.InvalidAlgorithm)
        val kid = header["kid"]?.asStringOrNull()
        if (kid.isNullOrBlank()) return fail(VerifyError.MissingKid)

        // 3) 查 JWKS 公钥；resolve 已经处理过 unknown-kid 的同步 refresh + throttle
        val jwk = try {
            keyProvider.resolve(kid)
        } catch (_: Throwable) {
            null
        } ?: return fail(VerifyError.UnknownKid)

        // 4) 验签
        val signingInput = "$headerB64.$payloadB64".encodeToByteArray()
        val signature = base64UrlDecodeOrNull(signatureB64) ?: return fail(VerifyError.Malformed)
        val publicKey = try {
            loadRsaPublicKey(jwk)
        } catch (_: Throwable) {
            return fail(VerifyError.SignatureInvalid)
        }
        val ok = try {
            publicKey.signatureVerifier().tryVerifySignatureBlocking(signingInput, signature)
        } catch (_: Throwable) {
            return fail(VerifyError.SignatureInvalid)
        }
        if (!ok) return fail(VerifyError.SignatureInvalid)

        // 5) 解 payload
        val payloadBytes = base64UrlDecodeOrNull(payloadB64) ?: return fail(VerifyError.Malformed)
        val payload = parseJsonObjectOrNull(payloadBytes) ?: return fail(VerifyError.Malformed)

        // 6) typ
        val typ = payload["typ"]?.asStringOrNull() ?: return fail(VerifyError.InvalidType)
        if (typ != expectedTyp) return fail(VerifyError.InvalidType)

        // 7) iss
        val iss = payload["iss"]?.asStringOrNull() ?: return fail(VerifyError.IssuerMismatch)
        if (iss != expectedIssuer) return fail(VerifyError.IssuerMismatch)

        // 8) exp
        val exp = payload["exp"]?.asLongOrNull() ?: return fail(VerifyError.Malformed)
        val nowSec = clock.now().epochSeconds
        if (nowSec >= exp + leewaySeconds) return fail(VerifyError.Expired)

        // 9) aud（必须是数组，且包含 requiredAudience）
        val audArray = payload["aud"]
            ?.takeIf { it is kotlinx.serialization.json.JsonArray }
            ?.jsonArray
            ?: return fail(VerifyError.AudienceMismatch)
        val audValues = audArray.mapNotNull { it.asStringOrNull() }
        if (requiredAudience !in audValues) return fail(VerifyError.AudienceMismatch)

        // 10) 必备业务 claim
        val sub = payload["sub"]?.asStringOrNull() ?: return fail(VerifyError.Malformed)
        val userId = sub.toLongOrNull() ?: return fail(VerifyError.Malformed)
        val deviceId = payload["device_id"]?.asStringOrNull()
            ?: return fail(VerifyError.Malformed)
        val sessionVersion = payload["session_version"]?.asLongOrNull()
            ?: return fail(VerifyError.Malformed)
        val scope = (payload["scope"]?.takeIf { it is kotlinx.serialization.json.JsonArray }
            ?.jsonArray
            ?: return fail(VerifyError.Malformed))
            .mapNotNull { it.asStringOrNull() }
        val jti = payload["jti"]?.asStringOrNull() ?: return fail(VerifyError.Malformed)
        val iat = payload["iat"]?.asLongOrNull() ?: 0L

        return VerifyResult.Success(
            UnifiedTokenClaims(
                issuer = iss,
                userId = userId,
                deviceId = deviceId,
                sessionVersion = sessionVersion,
                scope = scope,
                audience = audValues,
                expiresAt = exp,
                issuedAt = iat,
                jti = jti,
                kid = kid,
                tokenType = typ,
            ),
        )
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private fun loadRsaPublicKey(jwk: JwkRsaMaterial): RSA.PKCS1.PublicKey {
        val jwkJson = """
            {"kty":"${jwk.kty}","alg":"${jwk.alg}","use":"${jwk.use}","n":"${jwk.n}","e":"${jwk.e}"}
        """.trimIndent()
        val provider = CryptographyProvider.Default
        val rsa = provider.get(RSA.PKCS1)
        return rsa.publicKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(RSA.PublicKey.Format.JWK, jwkJson.encodeToByteArray())
    }

    private fun parseJsonObjectOrNull(bytes: ByteArray): Map<String, JsonElement>? = try {
        val text = bytes.decodeToString()
        Json.parseToJsonElement(text).jsonObject.toMap()
    } catch (_: Throwable) {
        null
    }

    private fun JsonElement.asStringOrNull(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.contentOrNull
    }

    private fun JsonElement.asLongOrNull(): Long? = try {
        (this as? JsonPrimitive)?.long
    } catch (_: Throwable) {
        null
    }

    private fun fail(e: VerifyError): VerifyResult = VerifyResult.Failure(e)

    public companion object {
        public const val DEFAULT_ISSUER: String = "privchat-server"
        public const val DEFAULT_REQUIRED_AUDIENCE: String = "privchat-application"
        public const val DEFAULT_LEEWAY_SECONDS: Long = 5
        public const val ALG_RS256: String = "RS256"
        public const val TYP_ACCESS: String = "access"
        public const val TYP_REFRESH: String = "refresh"

        /**
         * base64url-no-pad 解码；允许带 padding。失败返 null。
         */
        public fun base64UrlDecodeOrNull(s: String): ByteArray? {
            val pad = (4 - s.length % 4) % 4
            val standard = s.replace('-', '+').replace('_', '/') + "=".repeat(pad)
            return try {
                base64DecodeStandard(standard)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun base64DecodeStandard(s: String): ByteArray {
            val table = IntArray(128) { -1 }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for ((i, c) in alphabet.withIndex()) table[c.code] = i
            val cleaned = s.trimEnd('=')
            val out = ArrayList<Byte>(cleaned.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (c in cleaned) {
                if (c.code !in table.indices) throw IllegalArgumentException("invalid char")
                val v = table[c.code]
                require(v >= 0) { "invalid base64 char: $c" }
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer shr bits) and 0xFF).toByte())
                }
            }
            return out.toByteArray()
        }
    }
}

/**
 * spec §4 的 unified token 解析结果（验签 + claim 校验通过后的中性视图）。
 */
public data class UnifiedTokenClaims(
    val issuer: String,
    val userId: Long,
    val deviceId: String,
    val sessionVersion: Long,
    val scope: List<String>,
    val audience: List<String>,
    val expiresAt: Long,
    val issuedAt: Long,
    val jti: String,
    val kid: String,
    val tokenType: String,
)

/**
 * 验签 / 校验业务结果。
 *
 * - [Success] 携带解析出来的 claims；caller 仍要做 session_version DB 比对（[UnifiedTokenAuthenticator]）
 * - [Failure] 一律 fail-closed
 */
public sealed class VerifyResult {
    public data class Success(val claims: UnifiedTokenClaims) : VerifyResult()
    public data class Failure(val error: VerifyError) : VerifyResult()
}

/**
 * 结构化错误（与 spec §6.1 introspect `reason` 对齐）。
 */
public sealed class VerifyError {
    /** JWT 三段切分失败 / base64 解码失败 / payload JSON 不合法 / 必备 claim 缺失。 */
    public data object Malformed : VerifyError()

    /** header.alg 非 RS256。Phase A 锁 RS256（spec §4.4），其他算法直接 reject。 */
    public data object InvalidAlgorithm : VerifyError()

    /** header.kid 缺失或空字符串（spec §11.1 强制要求）。 */
    public data object MissingKid : VerifyError()

    /** kid 在 JWKS（含一次强制 refresh 后仍）找不到。 */
    public data object UnknownKid : VerifyError()

    /** 签名验证失败 / 公钥解码失败。 */
    public data object SignatureInvalid : VerifyError()

    /** `exp + leeway < now`。 */
    public data object Expired : VerifyError()

    /** `iss` 与 [RsaJwtVerifier.expectedIssuer] 不一致。 */
    public data object IssuerMismatch : VerifyError()

    /** `aud` 是数组但不含 `requiredAudience`，或 `aud` 不是数组。 */
    public data object AudienceMismatch : VerifyError()

    /** `typ` 与期望（默认 `access`）不一致。 */
    public data object InvalidType : VerifyError()
}
