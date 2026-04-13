package neton.logging

/**
 * 脱敏规则（v1 冻结）。
 *
 * 脱敏是内建能力，不是业务责任：日志输出前统一脱敏，业务代码永远不直接脱敏。
 *
 * v1 键名（实现层在序列化/写出前对下列键做脱敏，如 *** 或 [REDACTED]）：
 * - header: Authorization, Cookie, X-Api-Key, X-Access-Key, X-Secret-Key
 * - query/body: token, password, secret, mobile, phone, sms_code, verification_code,
 *   api_key, access_key, secret_key, credit_card 等
 */
object SensitiveFilter {
    /** header 中需脱敏的键名（小写匹配） */
    val headerKeys: Set<String> = setOf(
        "authorization",
        "cookie",
        "x-api-key",
        "x-access-key",
        "x-secret-key"
    )

    /** query/body 等需脱敏的键名（小写匹配） */
    val paramKeys: Set<String> = setOf(
        "token",
        "access_token",
        "refresh_token",
        "password",
        "new_password",
        "old_password",
        "secret",
        "secret_key",
        "api_key",
        "access_key",
        "mobile",
        "phone",
        "telephone",
        "sms_code",
        "verification_code",
        "otp",
        "credit_card",
        "bank_card"
    )
}
