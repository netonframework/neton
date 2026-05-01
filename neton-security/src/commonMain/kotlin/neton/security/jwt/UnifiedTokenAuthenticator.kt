package neton.security.jwt

/**
 * Unified token Authenticator（spec TOKEN_UNIFICATION_SPEC v1.3 §7.3）。
 *
 * 完整 Phase A verifier 流水线：
 *
 * 1. 从 `Authorization` header 取 Bearer token
 * 2. [RsaJwtVerifier] 本地 RS256 验签 + 校验 iss / aud / exp / typ / 必备 claims
 * 3. 查 [SessionVersionCache]：
 *    - hit && claim 版本 == cache 版本 → 放行（**不**调 introspect）
 *    - hit && claim 版本 != cache 版本 → 走 introspect 兜底（不假设方向）
 *    - miss / 异常 → 走 introspect 兜底
 * 4. introspect → `active=true` → 写 cache、返回 [UnifiedIdentity]
 * 5. introspect → `active=false` → 拒绝（带 reason）
 * 6. introspect 异常 → **fail closed**（带 IntrospectFailed reason）
 *
 * **不**做：
 * - 不接 [neton.security.jwt.RsaJwtVerifier] 之外的 verifier（HS256 legacy 是 dispatcher 的事）
 * - 不接 SecurityConfig / Ktor middleware（Step 4 wiring）
 * - 不接 `@SensitiveEndpoint` bypass（Step 5）
 *
 * 实现细节：所有 IO 异常都被吃掉，转成 [AuthOutcome.Failure]；上层只需要分辨成功 / 失败，
 * 不要解 throwable。
 */
public class UnifiedTokenAuthenticator(
    private val verifier: RsaJwtVerifier,
    private val introspector: UnifiedTokenIntrospector,
    private val sessionVersionCache: SessionVersionCache,
    /** session_version cache 写入 TTL；spec §7.3 默认 30s。 */
    private val sessionCacheTtlSeconds: Long = DEFAULT_SESSION_CACHE_TTL_SECONDS,
) {
    /**
     * 完整鉴权流程（spec §7.3 步骤 1-6）。
     */
    public suspend fun authenticate(token: String): AuthOutcome {
        if (token.isBlank()) return AuthOutcome.Failure(AuthFailureReason.Malformed)

        // Step 1: 本地验签
        val verifyResult = try {
            verifier.verify(token)
        } catch (_: Throwable) {
            return AuthOutcome.Failure(AuthFailureReason.IntrospectFailed)
        }
        val claims = when (verifyResult) {
            is VerifyResult.Failure -> return AuthOutcome.Failure(verifyResult.error.toFailureReason())
            is VerifyResult.Success -> verifyResult.claims
        }

        // Step 2: cache 查 session_version
        val cachedVersion = try {
            sessionVersionCache.get(claims.userId, claims.deviceId)
        } catch (_: Throwable) {
            null
        }

        // 命中 + 版本一致 → 跳过 introspect
        if (cachedVersion != null && cachedVersion == claims.sessionVersion) {
            return AuthOutcome.Success(claims.toIdentity())
        }

        // Step 3: introspect 兜底
        val intro = try {
            introspector.introspect(token)
        } catch (_: Throwable) {
            return AuthOutcome.Failure(AuthFailureReason.IntrospectFailed)
        }

        if (!intro.active) {
            val r = AuthFailureReason.fromIntrospectReason(intro.reason)
            return AuthOutcome.Failure(r)
        }

        // Step 4: introspect 通过 → 用 introspect 的真值更新 cache
        // 注意：spec §7.4 + 拍板第 8 条 —— 不能从 token claim 直接写 cache，必须 introspect active=true 后写
        val authoritativeVersion = intro.sessionVersion ?: claims.sessionVersion
        try {
            sessionVersionCache.set(
                uid = claims.userId,
                deviceId = claims.deviceId,
                version = authoritativeVersion,
                ttlSeconds = sessionCacheTtlSeconds,
            )
        } catch (_: Throwable) {
            // 写不进 cache 不阻塞鉴权；下次请求再来一次 introspect 兜底
        }

        // Step 5: 用 introspect 真值的 session_version 构建最终 Identity
        val effective = if (authoritativeVersion != claims.sessionVersion) {
            claims.copy(sessionVersion = authoritativeVersion)
        } else {
            claims
        }
        return AuthOutcome.Success(effective.toIdentity())
    }

    public companion object {
        public const val DEFAULT_SESSION_CACHE_TTL_SECONDS: Long = 30
    }
}

/**
 * 验证通过后的轻量 Identity 视图。spec §4 的 claim 子集 + jti（撤销追踪）。
 *
 * 暂不强制对接 [neton.security.identity.Identity] —— 该接口属于上层 Authenticator 框架，
 * dispatcher（Step 4）那一步会做适配。本类先表达 unified token 的语义。
 */
public data class UnifiedIdentity(
    val userId: Long,
    val deviceId: String,
    val sessionVersion: Long,
    val scope: List<String>,
    val audience: List<String>,
    val expiresAt: Long,
    val jti: String,
    val kid: String,
)

/** 鉴权结果。Phase A: caller 见到 [Failure] 必须 fail-closed（HTTP 401）。 */
public sealed class AuthOutcome {
    public data class Success(val identity: UnifiedIdentity) : AuthOutcome()
    public data class Failure(val reason: AuthFailureReason) : AuthOutcome()
}

/**
 * 鉴权失败原因（与 server introspect `reason` + verifier [VerifyError] 合集对齐）。
 */
public sealed class AuthFailureReason {
    public data object Malformed : AuthFailureReason()
    public data object InvalidAlgorithm : AuthFailureReason()
    public data object MissingKid : AuthFailureReason()
    public data object UnknownKid : AuthFailureReason()
    public data object SignatureInvalid : AuthFailureReason()
    public data object Expired : AuthFailureReason()
    public data object IssuerMismatch : AuthFailureReason()
    public data object AudienceMismatch : AuthFailureReason()
    public data object InvalidType : AuthFailureReason()
    public data object Revoked : AuthFailureReason()
    public data object SessionVersionMismatch : AuthFailureReason()
    public data object NotFound : AuthFailureReason()
    public data object IntrospectFailed : AuthFailureReason()
    public data class Other(val reason: String) : AuthFailureReason()

    public companion object {
        /** 把 server `IntrospectionResult.reason` 映射到本枚举。 */
        public fun fromIntrospectReason(reason: String?): AuthFailureReason = when (reason) {
            "expired" -> Expired
            "revoked" -> Revoked
            "version_mismatch" -> SessionVersionMismatch
            "signature_invalid" -> SignatureInvalid
            "unknown_kid" -> UnknownKid
            "not_found" -> NotFound
            null, "" -> NotFound
            else -> Other(reason)
        }
    }
}

/** 把 verifier 的 [VerifyError] 映射成 [AuthFailureReason]。 */
private fun VerifyError.toFailureReason(): AuthFailureReason = when (this) {
    VerifyError.Malformed -> AuthFailureReason.Malformed
    VerifyError.InvalidAlgorithm -> AuthFailureReason.InvalidAlgorithm
    VerifyError.MissingKid -> AuthFailureReason.MissingKid
    VerifyError.UnknownKid -> AuthFailureReason.UnknownKid
    VerifyError.SignatureInvalid -> AuthFailureReason.SignatureInvalid
    VerifyError.Expired -> AuthFailureReason.Expired
    VerifyError.IssuerMismatch -> AuthFailureReason.IssuerMismatch
    VerifyError.AudienceMismatch -> AuthFailureReason.AudienceMismatch
    VerifyError.InvalidType -> AuthFailureReason.InvalidType
}

private fun UnifiedTokenClaims.toIdentity(): UnifiedIdentity = UnifiedIdentity(
    userId = userId,
    deviceId = deviceId,
    sessionVersion = sessionVersion,
    scope = scope,
    audience = audience,
    expiresAt = expiresAt,
    jti = jti,
    kid = kid,
)
