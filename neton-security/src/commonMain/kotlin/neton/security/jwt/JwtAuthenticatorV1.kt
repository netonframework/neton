package neton.security.jwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlin.io.encoding.Base64
import neton.security.RequestContext
import neton.security.identity.AuthenticationException
import neton.security.identity.IdentityUser
import neton.security.identity.UserId
import neton.security.internal.HmacSha256

/**
 * JWT HS256 认证器，严格按 Neton-JWT-Authenticator-Spec-v1 实现
 */
class JwtAuthenticatorV1(
    private val secretKey: String,
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer "
) {
    val name: String = "jwt-v1"

    private val secretBytes = secretKey.encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 签发 JWT token
     * @param userId 用户 ID
     * @param roles 角色集合
     * @param permissions 权限集合
     * @param expiresInSeconds token 有效期（秒），默认 7200（2小时）
     * @param extraClaims 额外的自定义 claims（支持 String / Number / Boolean 值）
     * @return 签名后的 JWT 字符串（header.payload.signature）
     */
    fun createToken(
        userId: UserId,
        roles: Set<String> = emptySet(),
        permissions: Set<String> = emptySet(),
        expiresInSeconds: Long = 7200,
        extraClaims: Map<String, Any> = emptyMap()
    ): String {
        val nowSeconds = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000

        val headerJson = buildJsonObject {
            put("alg", "HS256")
            put("typ", "JWT")
        }

        val payloadJson = buildJsonObject {
            put("sub", userId.value.toString())
            put("iat", nowSeconds)
            put("exp", nowSeconds + expiresInSeconds)
            if (roles.isNotEmpty()) {
                put("roles", buildJsonArray { roles.forEach { add(it) } })
            }
            if (permissions.isNotEmpty()) {
                put("perms", buildJsonArray { permissions.forEach { add(it) } })
            }
            for ((k, v) in extraClaims) {
                when (v) {
                    is String -> put(k, v)
                    is Long -> put(k, v)
                    is Int -> put(k, v.toLong())
                    is Boolean -> put(k, v)
                    is Double -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

        val headerB64 = base64UrlEncode(headerJson.toString().encodeToByteArray())
        val payloadB64 = base64UrlEncode(payloadJson.toString().encodeToByteArray())
        val signingInput = "$headerB64.$payloadB64"
        val signature = HmacSha256.sign(secretBytes, signingInput.encodeToByteArray())
        val signatureB64 = base64UrlEncode(signature)

        return "$headerB64.$payloadB64.$signatureB64"
    }

    /**
     * 按 spec 第七节「解析失败映射规则」顺序执行
     * Authorization header 名 case-insensitive（HTTP 规范），Bearer 前缀 case-sensitive（spec 冻结）
     */
    suspend fun authenticate(context: RequestContext): IdentityUser? {
        val authHeader = context.headers.entries
            .firstOrNull { it.key.equals(headerName, ignoreCase = true) }
            ?.value ?: return null
        if (!authHeader.startsWith(tokenPrefix)) return null

        val token = authHeader.removePrefix(tokenPrefix).trim()
        if (token.isEmpty()) throw Auth("MissingToken", "Missing or invalid Bearer token", "Authorization")

        val parts = token.split(".")
        if (parts.size != 3) throw Auth("MissingToken", "Missing or invalid Bearer token", "Authorization")

        val header = parseJson(decodeBase64Url(parts[0]))
        val payload = parseJson(decodeBase64Url(parts[1]))
        val signatureBytes = decodeBase64UrlRaw(parts[2])

        val alg = (header["alg"] as? JsonPrimitive)?.content ?: ""
        if (alg != "HS256") throw Auth("InvalidAlgorithm", "Unsupported algorithm", "alg")

        val sub = (payload["sub"] as? JsonPrimitive)?.content?.trim() ?: ""
        if (sub.isEmpty()) throw Auth("InvalidUserId", "Invalid user id", "sub")

        val userId = try {
            UserId.parse(sub)
        } catch (e: AuthenticationException) {
            throw e
        } catch (_: Exception) {
            throw Auth("InvalidUserId", "Invalid user id", "sub")
        }

        val expSeconds = (payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (expSeconds == null || kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 >= expSeconds) {
            throw Auth("TokenExpired", "Token has expired", "exp")
        }

        val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()
        if (!HmacSha256.verify(secretBytes, signingInput, signatureBytes)) {
            throw Auth("InvalidSignature", "Invalid signature", "")
        }

        val roles = parseStringArray(payload["roles"])
        val perms = parseStringArray(payload["perms"])

        return IdentityUser(userId, roles.toSet(), perms.toSet())
    }

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.UrlSafe.encode(data).replace("=", "")

    private fun decodeBase64Url(s: String): String =
        decodeBase64UrlRaw(s).decodeToString()

    private fun decodeBase64UrlRaw(s: String): ByteArray {
        return try {
            val base64 = s.replace('-', '+').replace('_', '/')
            val padded = base64.padEnd(base64.length + (4 - base64.length % 4) % 4, '=')
            Base64.decode(padded)
        } catch (e: Exception) {
            throw Auth("MissingToken", "Missing or invalid Bearer token", "Authorization")
        }
    }

    private fun parseJson(s: String): JsonObject {
        return try {
            json.parseToJsonElement(s) as? JsonObject
                ?: throw Auth("MissingToken", "Missing or invalid Bearer token", "Authorization")
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: Exception) {
            throw Auth("MissingToken", "Missing or invalid Bearer token", "Authorization")
        }
    }

    private fun parseStringArray(elt: JsonElement?): List<String> {
        if (elt == null) return emptyList()
        val arr = elt as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun Auth(code: String, message: String, path: String): Nothing =
        throw AuthenticationException(code, message, path)
}
