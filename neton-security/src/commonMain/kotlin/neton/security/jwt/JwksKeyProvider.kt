package neton.security.jwt

/**
 * JWKS 公钥发布 → 本地查找的抽象（spec TOKEN_UNIFICATION_SPEC v1.3 §6.1 / §11.1）。
 *
 * neton-security 不持有 HTTP client；具体的 JWKS HTTP 实现由 application 端注入
 * （`PrivchatServiceClient.fetchJwks` 适配成 [JwksFetcher]）。本模块只负责按 `kid`
 * 查公钥 + 缓存 + unknown kid 节流刷新。
 */
public interface JwksKeyProvider {
    /**
     * 按 `kid` 查公钥；找不到（或刷新后仍然找不到）返回 null。
     *
     * 实现里**不**应该把 IO 异常吐给 caller —— [RsaJwtVerifier] 收到 null 会
     * 把整次验签判定为 [VerifyError.UnknownKid]，再让上层做 fail-closed 处理。
     */
    public suspend fun resolve(kid: String): JwkRsaMaterial?
}

/**
 * 一把 RSA 公钥的 JWKS 字段子集。
 *
 * - `n` / `e` 是 base64url-no-pad 大端字节（spec §6.1 example）
 * - `alg` / `kty` / `use` 这里只校验，不参与 RSA 解码
 */
public data class JwkRsaMaterial(
    val kid: String,
    val n: String,
    val e: String,
    val alg: String = "RS256",
    val kty: String = "RSA",
    val use: String = "sig",
)

/**
 * JWKS endpoint 的"网络访问"抽象。
 *
 * application 侧把 `PrivchatServiceClient.fetchJwks()` 包成本接口的实现注入
 * [JwksHttpProvider]。这层接口让 neton-security 完全 HTTP-free。
 */
public interface JwksFetcher {
    public suspend fun fetch(): List<JwkRsaMaterial>
}
