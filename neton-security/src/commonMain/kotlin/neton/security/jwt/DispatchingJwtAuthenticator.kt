package neton.security.jwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import neton.core.interfaces.Identity
import neton.security.Authenticator
import neton.security.RequestContext
import neton.security.identity.IdentityUser
import neton.security.identity.UserId

/**
 * 双验 dispatcher（spec TOKEN_UNIFICATION_SPEC v1.3 §10 Phase A · §8 dispatcher placement）。
 *
 * 路由规则按 JWT `iss` claim（**不**按 `alg`，alg 可伪造而 iss 在签名 payload 内）：
 *
 * | `iss` | `unifiedEnabled` | `verifyLegacyJwt` | 行为 |
 * |---|---|---|---|
 * | `privchat-server` | true  | * | unified verifier；失败返 null |
 * | `privchat-server` | false | * | **null**（unified 关闭，spec §10 Phase A 默认拒收 unified token）|
 * | 其他 / 空 / parse 失败 | * | true  | legacy `JwtAuthenticator.authenticate` |
 * | 其他 / 空 / parse 失败 | * | false | null（legacy 关闭）|
 *
 * **fail-fast**：构造时如 `enabled=false && verifyLegacyJwt=false`，
 * 抛 [IllegalStateException]，避免运行成"所有请求 401"的隐性故障。
 *
 * **现网兼容**：phase A 默认 `enabled=false, verifyLegacyJwt=true` —— 等价于直接用
 * legacy `JwtAuthenticator`，HS256 member token 行为不变。本类实例化和注册不会触发任何
 * unified 相关 IO（不预拉 JWKS、不调 introspect）。
 *
 * **Authenticator 契约**：返回 `null` = 凭据缺失/无效（按 anonymous 处理，框架由 Guard 决定 401）；
 * 抛异常 = 系统级错误（Redis down 等）。本类**只**抛 [IllegalStateException] in init；
 * 运行时所有"token 不收"路径一律返 null 不抛。
 */
public class DispatchingJwtAuthenticator(
    /** 现网 HS256 verifier；现有 `JwtAuthenticator` 直接传进来。 */
    private val legacy: Authenticator,
    /** Phase A unified verifier；可空（[unifiedEnabled] = false 时也可不构造，但通常都会 ship）。 */
    private val unified: UnifiedTokenAuthenticator?,
    private val unifiedEnabled: Boolean,
    private val verifyLegacyJwt: Boolean,
    /** Authorization header 名；与 [neton.security.jwt.JwtAuthenticator] 一致。 */
    private val headerName: String = "Authorization",
    private val tokenPrefix: String = "Bearer ",
    /** spec §0 锁定 issuer。 */
    private val unifiedIssuer: String = RsaJwtVerifier.DEFAULT_ISSUER,
) : Authenticator {

    init {
        require(unifiedEnabled || verifyLegacyJwt) {
            "DispatchingJwtAuthenticator: 'unified_token.enabled' and 'verify_legacy_jwt' both off — " +
                "this would 401 every request. Refusing to start; fix configuration."
        }
        if (unifiedEnabled) {
            requireNotNull(unified) {
                "DispatchingJwtAuthenticator: unified_token.enabled=true but no UnifiedTokenAuthenticator " +
                    "wired. Provide one (RsaJwtVerifier + JwksFetcher + Introspector + cache) or set " +
                    "unified_token.enabled=false."
            }
        }
    }

    override val name: String = "jwt-dispatcher"

    override suspend fun authenticate(context: RequestContext): Identity? {
        // 1) 取 Bearer token；缺失 / 格式错 → null（与 legacy JwtAuthenticator 一致行为）
        val token = extractBearer(context) ?: return null

        // 2) peek `iss`（不验签，不解释签名；只用于路由）
        val iss = peekIssOrNull(token)

        return when {
            iss == unifiedIssuer && unifiedEnabled -> dispatchUnified(token)
            iss == unifiedIssuer && !unifiedEnabled -> {
                // unified token 来了但路径关闭：phase A 默认 reject，不 fallback 到 legacy
                // （legacy verifier 也不会接受一个 RS256 + iss=privchat-server 的 token，但显式 null
                //  能避免日志里出现 "InvalidAlgorithm" 这种误导性错误）
                null
            }
            verifyLegacyJwt -> legacy.authenticate(context)
            else -> null
        }
    }

    /**
     * 调 [UnifiedTokenAuthenticator]，把 [AuthOutcome] 映射回 [Identity]。
     *
     * 失败一律返 null（phase A 默认 dormant；上层 Guard 决定是否 401）。
     */
    private suspend fun dispatchUnified(token: String): Identity? {
        val u = unified ?: return null // safety; init 里已 require 过，仍兜底
        return when (val outcome = u.authenticate(token)) {
            is AuthOutcome.Success -> outcome.identity.toFrameworkIdentity()
            is AuthOutcome.Failure -> null
        }
    }

    /**
     * 从 `Authorization` header 取 Bearer token；与 [neton.security.jwt.JwtAuthenticator] 同行为：
     * - header 名 case-insensitive
     * - prefix `Bearer ` case-sensitive（spec 冻结）
     * - 缺失 / 不带前缀 / 取出来空字符串 → null
     */
    private fun extractBearer(context: RequestContext): String? {
        val authHeader = context.headers.entries
            .firstOrNull { it.key.equals(headerName, ignoreCase = true) }
            ?.value ?: return null
        if (!authHeader.startsWith(tokenPrefix)) return null
        return authHeader.removePrefix(tokenPrefix).trim().takeIf { it.isNotEmpty() }
    }

    public companion object {
        /**
         * 不验签解出 `iss` claim。失败返 null（让 caller 走 legacy 路径或拒收）。
         *
         * **不**做：签名 / typ / aud / exp / 任何业务 claim 校验 —— 那是 verifier 的事，
         * 这里只用来 routing。任何对 routing 的"提前拒"都属于 verifier 的 fail-closed。
         */
        public fun peekIssOrNull(token: String): String? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payloadBytes = RsaJwtVerifier.base64UrlDecodeOrNull(parts[1]) ?: return null
            return try {
                val obj = Json.parseToJsonElement(payloadBytes.decodeToString()).jsonObject
                (obj["iss"] as? JsonPrimitive)?.contentOrNull
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * 把 unified token 的轻量 Identity 视图转换成 framework `Identity`。
 *
 * Phase A 映射约定（plan B §7.2）：
 * - `userId` (Long) → [UserId]
 * - `roles = emptySet`（unified token 不携带 roles；admin 等仍由 application RBAC 实时判断）
 * - `permissions = scope.toSet`（spec §6.1.1：scope 是 token capability，不是最终 RBAC）
 *
 * **关键边界**：admin 路由的实际授权 **必须** 由 application 自己的 `PermissionEvaluator`
 * 决定（见 `privchat-application/application/.../config/SecurityConfig.kt`），
 * 把 scope 当 permissions 只是为了让现有 Guard 模型有数据可读；**不构成**授权依据。
 */
private fun UnifiedIdentity.toFrameworkIdentity(): IdentityUser = IdentityUser(
    userId = UserId(userId.toULong()),
    roles = emptySet(),
    permissions = scope.toSet(),
)
