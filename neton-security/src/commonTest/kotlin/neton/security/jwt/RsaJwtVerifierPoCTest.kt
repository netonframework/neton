package neton.security.jwt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RS256-on-Native PoC（spec TOKEN_UNIFICATION_SPEC v1.3 Phase A · Plan H Risk #1）。
 *
 * 这层测试**不是**正式 verifier；目的只有一个：在每个 native target 实证
 * `cryptography-kotlin` 0.6.0 能把 JWKS 里的 `(n, e)` 转成 RSA public key
 * 并验证 RSASSA-PKCS1-v1_5 + SHA-256 的 JWT 签名。
 *
 * 通过 = Step 3 可以放心写正式 [neton.security.jwt.RsaJwtVerifier]；
 * 单 target 失败 = 该 target 走 introspect-only fallback（spec §10 phase A 容忍）。
 *
 * Golden vector 用 `privchat-server/.local/keys/jwt/v1.pem` 通过 Python `cryptography` 库生成，
 * 见 commit message。Vector 是确定性的：同一 PEM 同一 header/payload 出来的签名永不变。
 *
 * 这层测试**不引入** kotlinx.serialization、JWKS HTTP client、session_version cache、
 * dispatcher、SecurityConfig wiring 等任何上层抽象 —— PoC 越窄越好。
 */
class RsaJwtVerifierPoCTest {

    /**
     * Golden RSA public key in JWK form（base64url-no-pad, big-endian）。
     *
     * 来源：`openssl rsa -in v1.pem -pubout` 的公钥，n/e 通过
     * `cryptography.hazmat.primitives.asymmetric.rsa.RSAPublicNumbers` 提取并 base64url-no-pad
     * 编码。
     */
    private val publicJwkN =
        "yK3eAX8z1ctMqvGq-90MHZOC2tHBrUVa4eLm7s9y00zmnLkkY-L4KPYFbDYoY7uca0VAHoNMp9DJE9g7ypNrw" +
            "1smr_heBpCLSCfg7lKGPWKWP8BzEPlXef3wMywQnnU4L43xDF6pt8CNnlcVV_A7dl86u2ugxIFHkqUZcxJ" +
            "YgcCogAI6ZeZSJBHLWj1z8zyhxWldmKJji9nZyFywcOJ3ur8xNMcALwFX8DuIduGhuXd4QcH9YtF0hEWJZ" +
            "KgHHoAaAxelRBsyTDc6AWvLeXAJQlzvOLWRiZsCMSPozvD5GVyyF9cdOr-PaWSbymGwHFPsWVcCzcfhImb" +
            "yEIsFRZKo6Q"
    private val publicJwkE = "AQAB"

    /**
     * Golden JWT，header `{alg:RS256, typ:JWT, kid:v1}`，payload 是 spec §4 必备 claim 的最小集。
     *
     * 这是用 `privchat-server/.local/keys/jwt/v1.pem` 离线签出来的，不会在 PoC 里再跑签名路径
     * （PoC 的目标是验签 + 加载公钥，不验证 server 的签名实现）。
     */
    private val goldenToken =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0." +
            "eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNh" +
            "dGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMCwi" +
            "anRpIjoicG9jLWp0aSIsInR5cCI6ImFjY2VzcyIsImRldmljZV9pZCI6InBvYy1kZXYiLCJzZXNzaW9u" +
            "X3ZlcnNpb24iOjEsInNjb3BlIjpbInVzZXIiXSwiYnVzaW5lc3Nfc3lzdGVtX2lkIjoicHJpdmNoYXQt" +
            "YXBwbGljYXRpb24iLCJhcHBfaWQiOiJwb2MifQ." +
            "B1clh2esJ8HR1lOmolf0EEKWCNN6kNVljLlc3VpAqlsAyK_iFi1NZQdToyxSADFISWqqxajCmA5Dw_lm" +
            "TqdMQoReHjePwjRxpyN4iAtV_X7C8ujv86UaeeEhLemgdvLnbZyVRUCx_bFQ7pUZOAbZYwSn9hMpDy5h" +
            "2k7swx7yu-JUqyCLHLdbYw-nhKzElvyVU1PGIF80XB8hJVvaLds46_ZZs8fGvXASYj7oyVe5Vc1h-s5J" +
            "c9ErRi3OCsTkFXRDZ6Q0lnobaBoZYQM1E1R2hmjj2qt3uY2n_kHLP80UOaTu9ub8JJCrSU_cv7f3LnVz" +
            "_gLpd4Q92RHmoDsXo1B4bQ"

    @Test
    fun valid_signature_verifies() {
        val parts = goldenToken.split('.')
        assertEquals(3, parts.size, "JWT must have 3 dot-separated parts")
        val (h, p, s) = Triple(parts[0], parts[1], parts[2])
        val signingInput = "$h.$p".encodeToByteArray()
        val signature = base64UrlDecode(s)
        // sanity：2048-bit RSA 签名固定 256 字节
        assertEquals(256, signature.size)

        val publicKey = loadRsaPublicKeyFromJwkComponents(publicJwkN, publicJwkE)
        val ok = publicKey.signatureVerifier()
            .tryVerifySignatureBlocking(signingInput, signature)

        assertTrue(ok, "RS256 verify must succeed for golden JWT")
    }

    @Test
    fun tampered_signature_fails_verification() {
        val parts = goldenToken.split('.')
        val (h, p, s) = Triple(parts[0], parts[1], parts[2])
        val signingInput = "$h.$p".encodeToByteArray()

        // 翻最后一字节
        val sigBytes = base64UrlDecode(s)
        sigBytes[sigBytes.lastIndex] = (sigBytes.last().toInt() xor 0x01).toByte()

        val publicKey = loadRsaPublicKeyFromJwkComponents(publicJwkN, publicJwkE)
        val ok = publicKey.signatureVerifier()
            .tryVerifySignatureBlocking(signingInput, sigBytes)

        assertFalse(ok, "tampered signature must NOT verify")
    }

    @Test
    fun tampered_payload_fails_verification() {
        val parts = goldenToken.split('.')
        val (h, p, s) = Triple(parts[0], parts[1], parts[2])
        val signature = base64UrlDecode(s)

        // 把 payload b64url 末位换一个字符（仍合法 base64url 字符），保证 payload bytes 变化
        val tamperedPayload = p.dropLast(1) + if (p.last() == 'A') 'B' else 'A'
        val signingInput = "$h.$tamperedPayload".encodeToByteArray()

        val publicKey = loadRsaPublicKeyFromJwkComponents(publicJwkN, publicJwkE)
        val ok = publicKey.signatureVerifier()
            .tryVerifySignatureBlocking(signingInput, signature)

        assertFalse(ok, "tampered payload must NOT verify")
    }

    // ─────────────────────── PoC helpers (no production claim) ───────────────────────

    /**
     * 把 JWK 里的 `n` / `e` 组装成 cryptography-kotlin 可消费的 JWK JSON，再走
     * `RSA.PKCS1` 的 `publicKeyDecoder(SHA256)` 解码。
     *
     * Phase A 正式实现时这段会搬到 `RsaJwtVerifier`；PoC 阶段写在 test 里就行。
     */
    private fun loadRsaPublicKeyFromJwkComponents(
        n: String,
        e: String,
    ): RSA.PKCS1.PublicKey {
        val jwk = """{"kty":"RSA","alg":"RS256","use":"sig","n":"$n","e":"$e"}"""
        val provider = CryptographyProvider.Default
        val rsa = provider.get(RSA.PKCS1)
        return rsa.publicKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(RSA.PublicKey.Format.JWK, jwk.encodeToByteArray())
    }

    /**
     * base64url-no-pad 解码。Native target 上没有现成的 stdlib 实现；写一份最小版本即可。
     * 正式实现可以走 cryptography-kotlin 自己的 base64url helper，PoC 不引依赖。
     */
    private fun base64UrlDecode(s: String): ByteArray {
        val pad = (4 - s.length % 4) % 4
        val standard = s.replace('-', '+').replace('_', '/') + "=".repeat(pad)
        return base64DecodeStandard(standard)
    }

    /**
     * 标准 base64 解码。手写 ~30 行，规避 KMP target 间 stdlib 差异。
     */
    private fun base64DecodeStandard(s: String): ByteArray {
        val table = IntArray(128) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for ((i, c) in alphabet.withIndex()) table[c.code] = i

        val cleaned = s.trimEnd('=')
        val out = ArrayList<Byte>(cleaned.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
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
