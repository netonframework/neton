package neton.security.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 与 openssl 的互操作。
 *
 * 支付宝 RSA2、微信支付 V3 的签名都是这套 `SHA256withRSA`，而实现错了对方只会回
 * 一句"验签失败"，不会告诉你差在哪 —— 所以用 openssl 生成的真实密钥对与签名做基准，
 * 双向都验：能验对方签的，也要让对方能验我们签的。
 */
class RsaSha256Test {

    private val privateKey = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCq9RtsUYAmTL/mjJ+84p6IU5dYT030twPgk9fjzIE8cyj1PteNEag0kg+Aedgezj6XNbeLaR++DWont7uln3qfqmpCpvl1rXbsUcabkyIgVoL4eMY93xt/FrNIrqSymvX2wkxFQd5j2CkOewiM8NCTufXoEd8vLbZMRiNNg5rALUpumUYk/gDxJPSfYHhfh6kolvCrPIHB1DzW52Vb+WPxz+bCFoo7hcW8SfnieKxA1jgY/H/wMCAg9TV3GdiYjzvuM/AlbZJCFs/AETFJubnAoiSKgbKspSaD5OT5paH/J+IwrJyuOyBqgubOdDgmocg43s4pEeDWPavy44yJ6avBAgMBAAECggEAT8PBIc79DeGtf/KI7WaHNXBbIxcNdmqV4ojYqC7Y9c19hL/nbqiYZL7pgLZZAjaUuZSUqPVJnDFCIHn3kZVRb4HhxmuF5UQkQqr9EcWanKAAx9ICHQgmGiwLRpRFwBfRP2r0jzPmgYtvzJPXL3uEtgiEFd2Q1sBrWDc5bYdEAvnat8KJHxq2wfeuXXYPtyCUtTmlRdlcDkPIVBfMhqxj/z7TanWwRg4ukuOQFYZLdQgfSMCF0Fyn2REKunczN7v4LJNYHPRIgHPfvWujA1+GBFCKdmWjsPA0MCBCL1tWXK2HmkR7pbhMxp1Q9C7T8Kzj0cu0sd5AGIZN9m7Z5WKxSQKBgQDltlH2on+flqqjwS1E4t+9orilDW+GaWfJD5HFzNYDnndc+3P/r+eUGDMECqZyaXMhSg/MEq9mkH2URIuW6KGb+0otZrasYIHtnmoknSWk0yctxd1+L2V63Jd19s+XnXWqZv3Us7TDmEaMwVuoP9QSPRaOWcbE/dflAjWdXH+V9wKBgQC+hYAD7heoF2W8EhYXD/N0MImiGo4xP2jMDe0m4XYJPiQXGg+004emsJRhyG4jcYULHRZoRm8Xc1vRyvo94OUJLkZdyuJR5BcM0WdI070CZLGCdLzm2+MVSgA1GLl+iPqIfvQJC1pjdgI4UOlGnfF0jwiTapp51wm204EkIwt+BwKBgQDBKGIbheDTDRpHwHSUbEG/cEjbYUTaPV/sDY+CSA/d0y6DnV2ZLw0H1qFvUJVNt6X75A8Mhtm+4Nj4B/to1gyu4MsrCiepIy2d5YtTZmD1DCjxsGPja29ltIAXzYYZ82mx9BCU/teNcUpBqYWtIJ7vBzckVBF0LA+Snhz/SXxvWQKBgCfxfUFVrYgEP8QKVq9HHNeDRZfC0YTpsmL1mH7KTiDp8k8Vm61hm9MKulE14EF2D1qhIo2CFtBn0xxM3eITQHGITiBj5MceduatEGZoXfweeEjNiL0t5JIWDa0UHe+1cDElzKwIwU6Q8y4zaHTxsCmrwzSE6RYaS2MVPMICxuoJAoGBALfZhx6WQ2ewP96PixtSAb5GjZRK+uOZj/BV7u/wUHfgxpdVFONPpj4+rkRNZXucVUBmVdpkVKaBllb5pZLRUX8OSVnP9bCeWVfZ5X8GzYwntmNUsIxfVJj1IBylyRu50nf6GQRY0axI1yMnZICmLZUDprXDR2K3msjwGmdJOD9z"
    private val publicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqvUbbFGAJky/5oyfvOKeiFOXWE9N9LcD4JPX48yBPHMo9T7XjRGoNJIPgHnYHs4+lzW3i2kfvg1qJ7e7pZ96n6pqQqb5da127FHGm5MiIFaC+HjGPd8bfxazSK6kspr19sJMRUHeY9gpDnsIjPDQk7n16BHfLy22TEYjTYOawC1KbplGJP4A8ST0n2B4X4epKJbwqzyBwdQ81udlW/lj8c/mwhaKO4XFvEn54nisQNY4GPx/8DAgIPU1dxnYmI877jPwJW2SQhbPwBExSbm5wKIkioGyrKUmg+Tk+aWh/yfiMKycrjsgaoLmznQ4JqHION7OKRHg1j2r8uOMiemrwQIDAQAB"
    private val payload = "test-payload-for-signing".encodeToByteArray()

    /** openssl 用私钥签出来的，我们必须能用公钥验过。 */
    private val opensslSignature = "ldb2L1wFWM07r7Aw/c1MEMe0gE6a5l8N6j1SSSpuUrWPHwdzL+hsWDN752oDZhGhl3cm+vZFXsCtP/F7vyn3fJRAJ8t+OrlECKeIPeTAo0hg6gsExbQi9oNyXPlgx2WnkqNnh1NKSYXdthR6slI7J/gx20Wk3slyglg6L4+oOLUCJl2hh4IJkEsjEri9v/ac0BRv6TzCfkOHYoB/uOsZHCxQoARV9p67yHNC9nl/kGeStpEc6ZVdSBerdFncd0ZKhTxgUA1bVmqISsj9rL/vlOfF1twIORfYat4VHAxbahzQHF2rqG4mhECQAfvgKY+trtMqtAvvknhNlHDPLOc62A=="

    @Test
    fun verifiesSignatureMadeByOpenssl() {
        assertTrue(
            RsaSha256.verifyBase64(publicKey, payload, opensslSignature),
            "验不过 openssl 的签名，说明摘要算法或填充方式与标准不一致",
        )
    }

    /** 我们签的，用同一把公钥必须能验过（等价于对方能验我们的请求）。 */
    @Test
    fun ownSignatureRoundTrips() {
        val mine = RsaSha256.signBase64(privateKey, payload)
        assertTrue(RsaSha256.verifyBase64(publicKey, payload, mine))
    }

    /** 内容被改动必须验不过 —— 这正是签名要防的事。 */
    @Test
    fun rejectsTamperedPayload() {
        assertFalse(
            RsaSha256.verifyBase64(publicKey, "tampered".encodeToByteArray(), opensslSignature),
        )
    }

    /** 畸形签名按验签失败处理，不能把异常抛给上层：回调是公网入口。 */
    @Test
    fun malformedSignatureIsRejectedNotThrown() {
        assertFalse(RsaSha256.verifyBase64(publicKey, payload, "not-base64!!!"))
        assertFalse(RsaSha256.verifyBase64(publicKey, payload, ""))
    }

    /** PEM 头尾与换行应被容忍：各家文档给的密钥形式不一。 */
    @Test
    fun acceptsPemWrappedKeys() {
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            publicKey.chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----"
        assertTrue(RsaSha256.verifyBase64(pem, payload, opensslSignature))
    }
}
