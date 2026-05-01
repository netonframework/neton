package neton.security.jwt

/**
 * Server `/api/service/auth/introspect` 的 application 端入口抽象
 * （spec TOKEN_UNIFICATION_SPEC v1.3 §6.1）。
 *
 * neton-security 不持有 service master key 或 HTTP client；具体调用由 application 端
 * 把 `PrivchatServiceClient.introspectAuthToken` 包成 [UnifiedTokenIntrospector] 实现注入。
 *
 * **fail-closed 契约**：实现里 IO 异常 / server 5xx → 抛出，由 [UnifiedTokenAuthenticator]
 * 捕获并拒绝（不要返回"假阳性"的 active=true）。
 */
public interface UnifiedTokenIntrospector {
    public suspend fun introspect(token: String): IntrospectionResult
}

/**
 * Introspect 业务结果（snake_case 字段在 client 层已转好，本类是中性 KMP 数据）。
 *
 * `reason` 取值集合（与 server 端 `unified_token_service.rs::IntrospectResult::inactive` 对齐）：
 * `expired` / `revoked` / `version_mismatch` / `signature_invalid` / `unknown_kid` / `not_found`。
 */
public data class IntrospectionResult(
    val active: Boolean,
    val userId: Long? = null,
    val deviceId: String? = null,
    val sessionVersion: Long? = null,
    val scope: List<String>? = null,
    val audience: List<String>? = null,
    val expiresAt: Long? = null,
    val jti: String? = null,
    val reason: String? = null,
)
