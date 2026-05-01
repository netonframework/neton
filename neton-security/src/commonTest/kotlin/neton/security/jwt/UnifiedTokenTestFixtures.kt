package neton.security.jwt

/**
 * 共享 RS256 unified token 测试 fixtures。
 *
 * Golden vector 来自 `privchat-server/.local/keys/jwt/v1.pem`，通过 Python `cryptography`
 * 库一次性生成；同 PEM 同 header/payload 出来的签名永远一致。
 *
 * 这些 fixture 不是产品 API；只在 commonTest 内供 verifier / authenticator / provider 测试用。
 */
internal object UnifiedTokenTestFixtures {
    // ──────────── JWK (matches privchat-server v1.pem) ────────────

    const val KID_V1: String = "v1"

    const val PUBLIC_JWK_N: String =
        "yK3eAX8z1ctMqvGq-90MHZOC2tHBrUVa4eLm7s9y00zmnLkkY-L4KPYFbDYoY7uca0VAHoNMp9DJE9g7ypNrw" +
            "1smr_heBpCLSCfg7lKGPWKWP8BzEPlXef3wMywQnnU4L43xDF6pt8CNnlcVV_A7dl86u2ugxIFHkqUZcxJ" +
            "YgcCogAI6ZeZSJBHLWj1z8zyhxWldmKJji9nZyFywcOJ3ur8xNMcALwFX8DuIduGhuXd4QcH9YtF0hEWJZ" +
            "KgHHoAaAxelRBsyTDc6AWvLeXAJQlzvOLWRiZsCMSPozvD5GVyyF9cdOr-PaWSbymGwHFPsWVcCzcfhImb" +
            "yEIsFRZKo6Q"

    const val PUBLIC_JWK_E: String = "AQAB"

    val DEFAULT_JWK: JwkRsaMaterial = JwkRsaMaterial(
        kid = KID_V1,
        n = PUBLIC_JWK_N,
        e = PUBLIC_JWK_E,
    )

    // ──────────── Tokens (all signed by v1.pem) ────────────
    // payload base values: iss=privchat-server, sub=42, aud=[privchat-application,privchat-server],
    // iat=1700000000, exp=2000000000 (year 2033), jti=poc-jti, typ=access, device_id=poc-dev,
    // session_version=1, scope=[user], business_system_id=privchat-application, app_id=poc.

    /** 标准 valid token；exp=2033 远期，session_version=1。 */
    const val TOKEN_VALID: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNhdGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMCwianRpIjoicG9jLWp0aSIsInR5cCI6ImFjY2VzcyIsImRldmljZV9pZCI6InBvYy1kZXYiLCJzZXNzaW9uX3ZlcnNpb24iOjEsInNjb3BlIjpbInVzZXIiXSwiYnVzaW5lc3Nfc3lzdGVtX2lkIjoicHJpdmNoYXQtYXBwbGljYXRpb24iLCJhcHBfaWQiOiJwb2MifQ.B1clh2esJ8HR1lOmolf0EEKWCNN6kNVljLlc3VpAqlsAyK_iFi1NZQdToyxSADFISWqqxajCmA5Dw_lmTqdMQoReHjePwjRxpyN4iAtV_X7C8ujv86UaeeEhLemgdvLnbZyVRUCx_bFQ7pUZOAbZYwSn9hMpDy5h2k7swx7yu-JUqyCLHLdbYw-nhKzElvyVU1PGIF80XB8hJVvaLds46_ZZs8fGvXASYj7oyVe5Vc1h-s5Jc9ErRi3OCsTkFXRDZ6Q0lnobaBoZYQM1E1R2hmjj2qt3uY2n_kHLP80UOaTu9ub8JJCrSU_cv7f3LnVz_gLpd4Q92RHmoDsXo1B4bQ"

    /** exp = 2001-09-09 (epoch 1000000000)，远早于现在；测 [VerifyError.Expired]。 */
    const val TOKEN_EXPIRED: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNhdGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MTAwMDAwMDAwMCwianRpIjoicG9jLWp0aSIsInR5cCI6ImFjY2VzcyIsImRldmljZV9pZCI6InBvYy1kZXYiLCJzZXNzaW9uX3ZlcnNpb24iOjEsInNjb3BlIjpbInVzZXIiXSwiYnVzaW5lc3Nfc3lzdGVtX2lkIjoicHJpdmNoYXQtYXBwbGljYXRpb24iLCJhcHBfaWQiOiJwb2MifQ.n-FINiti73kMX-1VnIDOAMJg6v2d2C6Kz6HAJwH4naTa4d1CDCyLLvWuoYftASh2yD0-w9t1YPiCqv9g-_Ai5cyHbRHIiYUKFtC9sX3xD0BbFO4MYuArwSxjWJ3FhvEcPgd2dqgg5tFJwzjPeZrmipMPpGnsrSj_2L1eGLzHZz_m_24f_7OVJnaHW0PPoPrz5TduqamBlWfyqd4vn3AZeieTZyObTqC96E_MfvrCbffs9QVRYnhET_LGvohRgTVzaG1f1ZizGoxTalqiZm9fjMV6fRfHbkHbU8BaqxZoLaZAr0kJ_WEKvDqZyqcYpfJqRE1vGdtEZ2rcFF2FJrDIEQ"

    /** iss = "malicious-issuer"。 */
    const val TOKEN_WRONG_ISS: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJtYWxpY2lvdXMtaXNzdWVyIiwic3ViIjoiNDIiLCJhdWQiOlsicHJpdmNoYXQtYXBwbGljYXRpb24iLCJwcml2Y2hhdC1zZXJ2ZXIiXSwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjIwMDAwMDAwMDAsImp0aSI6InBvYy1qdGkiLCJ0eXAiOiJhY2Nlc3MiLCJkZXZpY2VfaWQiOiJwb2MtZGV2Iiwic2Vzc2lvbl92ZXJzaW9uIjoxLCJzY29wZSI6WyJ1c2VyIl0sImJ1c2luZXNzX3N5c3RlbV9pZCI6InByaXZjaGF0LWFwcGxpY2F0aW9uIiwiYXBwX2lkIjoicG9jIn0.OMd_wv5FktoQTZdu5npFN5bQNkNrPaHSVkZ1JzlXxt9dqHBVJceiwTsozb29miMZJQbuo5tQJjktJTbkIC6A5N_I_UL6o-nTE2djk4NNM4J88dn1S0QW3gZdjXecLC433_rUyA9blx6npZ_biC2ZJqQwUwkmFAaCjLTaRPr75zJyVWE7XvylVKJGN7w8Tt-RozrvrGlAFBjEq3dn5lrM-jtgj1GwriDxBWT1a1GvJaJvKfTaqVtanjRqUPflLOtXBNLMHcGvRRDxeWZBsLq41KrUPaHOPL91l0-O-Ysgs3Qx1sm7a1x3c2i1ywBF_1P5jYQVb6XKjarXRN9L-UGeSw"

    /** aud = ["privchat-server"]，缺 privchat-application。 */
    const val TOKEN_WRONG_AUD: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1zZXJ2ZXIiXSwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjIwMDAwMDAwMDAsImp0aSI6InBvYy1qdGkiLCJ0eXAiOiJhY2Nlc3MiLCJkZXZpY2VfaWQiOiJwb2MtZGV2Iiwic2Vzc2lvbl92ZXJzaW9uIjoxLCJzY29wZSI6WyJ1c2VyIl0sImJ1c2luZXNzX3N5c3RlbV9pZCI6InByaXZjaGF0LWFwcGxpY2F0aW9uIiwiYXBwX2lkIjoicG9jIn0.gu4l57ojoovxBUMBi25C9IxLfpmi6en4eIp7zm6vyXlTEGgNQT9TpLoUyDge0IKkov-85AOY8Rj68OJcLKjC-BC4W_BqEea4ZEVMRN9135kzvtrbHBS_WQsNj_g3YvpDp6leNhZDP84BtCEgzRdE056owv4Jsc483MciVFTVA3Rjs-y_2xIbiPyKA_Va8-HtCKSpEmptHtn_RRHmMsJjUjFGJykny6KVbR8JSXRHEWkMbEg8eX1FotC8vURsIAzPoF3YMju9FzTUURT6k06vOUaDiPO9peKqaoh4DeZ9Y6NmI4M6-SlvsFfeRYtLuanaDZffeSXeMe7IReU0GGImag"

    /** typ = "refresh"，本来是 refresh token，不应该在 Authorization header 里出现。 */
    const val TOKEN_TYP_REFRESH: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNhdGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMCwianRpIjoicG9jLWp0aSIsInR5cCI6InJlZnJlc2giLCJkZXZpY2VfaWQiOiJwb2MtZGV2Iiwic2Vzc2lvbl92ZXJzaW9uIjoxLCJzY29wZSI6WyJ1c2VyIl0sImJ1c2luZXNzX3N5c3RlbV9pZCI6InByaXZjaGF0LWFwcGxpY2F0aW9uIiwiYXBwX2lkIjoicG9jIn0.gHnJWW3lkHQYju1wR2WZS84-xTMc4XQO2xxwgTTypQy4Z9_VtLx5IHqRqQOCswnV8yrzoJvfGJY638OsYDEVVknCjT64b_z1QrTEc3JDnF6jJpLK0590FADQvfdp5HQ1csV9sKhaFVfeNQPfFiQB3_WIuZnyKhrhBANMXLdkmlAPCiZPeVslG6IeMeVZeuRalh8H4YN5GQ8kclJsY4snmq3sqjcoxF7g1vINDfEDiwSd7T8z8BSbSh_9yGjKtQwytakGWjEaXSOpUD0TFR6qmbuSnEFwVmm2aH5QSeg9xGEQqtCGvgywsmlbQ_FiHRUFBJn3AYmVlm5NUCMEKpVByQ"

    /** kid = "unknown-kid"。 */
    const val TOKEN_UNKNOWN_KID: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InVua25vd24ta2lkIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNhdGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMCwianRpIjoicG9jLWp0aSIsInR5cCI6ImFjY2VzcyIsImRldmljZV9pZCI6InBvYy1kZXYiLCJzZXNzaW9uX3ZlcnNpb24iOjEsInNjb3BlIjpbInVzZXIiXSwiYnVzaW5lc3Nfc3lzdGVtX2lkIjoicHJpdmNoYXQtYXBwbGljYXRpb24iLCJhcHBfaWQiOiJwb2MifQ.dgi0st_-OfEpJ46UWIkiHl1cJVulprOGfMaPwQK_HQVF1BJuYJi89dPdNLntVBh9hcAh_DCrGuhjLKNumTjkZ3DfeSiVWVnl9bEECZZB1keuoDlGqBH9kmwEv-rkTdJewLhWRg2-K4nncphUzqfrZqijhNNAJmH7MDKW99IY7H6vImHFiYujnJXXgb7ydqb9GJ1yajjsJURYZ1XVved7FniRINCvgXp6wH2Zf-_hyyg9b6CQLGbbgESqcNU4_68kAq2bORJTU4NHip8TCkGFYbmsDfFR9CS2BSnjPrE48gPc1_gDxrPO_8QKO4pmKgwQ77TZY-1oWfglHW5IosHHOg"

    /** session_version=5；用于 cache mismatch 测试。 */
    const val TOKEN_SV5: String =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InYxIn0.eyJpc3MiOiJwcml2Y2hhdC1zZXJ2ZXIiLCJzdWIiOiI0MiIsImF1ZCI6WyJwcml2Y2hhdC1hcHBsaWNhdGlvbiIsInByaXZjaGF0LXNlcnZlciJdLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MjAwMDAwMDAwMCwianRpIjoicG9jLWp0aSIsInR5cCI6ImFjY2VzcyIsImRldmljZV9pZCI6InBvYy1kZXYiLCJzZXNzaW9uX3ZlcnNpb24iOjUsInNjb3BlIjpbInVzZXIiXSwiYnVzaW5lc3Nfc3lzdGVtX2lkIjoicHJpdmNoYXQtYXBwbGljYXRpb24iLCJhcHBfaWQiOiJwb2MifQ.RfNek2ppdvLfyjLWWcr_dNPkbgMVXaebNFmTwlY-nmbpg0l-MMdBlUkkiXktWm5z8bJpfl8w1I9qTfLuKhLpiFEj_H12_kP8j1J6uakKAHH2sn4mfLAjkYeP3_G0AC2DO5GddRiX98zyjNpRQEZgIwa2fq95l0teOIf99n6spz_IjUJZRdLUSb1ilIAdrqjmpp5j9pk4cY7HFCNy0VOP9-wij_dH7TH-5GtotMGZYbwir33xZnRM4EaUOY7rvaaXI7EvURpMYJE7LmJUkr6NZ7AF6NTBRMdzcFO9JuYpuCRGbB1exaa4w1AWjnFhcSoIVvxvcrhuy7vk1rZ4ClzvOg"
}

/** 静态 JwksKeyProvider：直接从 map 查；用于不需要 fetcher 行为的纯 verifier 测试。 */
internal class StaticJwksKeyProvider(
    private val keys: Map<String, JwkRsaMaterial>,
) : JwksKeyProvider {
    override suspend fun resolve(kid: String): JwkRsaMaterial? = keys[kid]
}

/** 计数 fetcher：测试 [JwksHttpProvider] 缓存命中、强制刷新次数。 */
internal class CountingJwksFetcher(
    private val keys: List<JwkRsaMaterial>,
) : JwksFetcher {
    var fetchCount: Int = 0
        private set

    override suspend fun fetch(): List<JwkRsaMaterial> {
        fetchCount += 1
        return keys
    }
}
