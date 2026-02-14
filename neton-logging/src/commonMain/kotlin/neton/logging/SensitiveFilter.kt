package neton.logging

/**
 * 脱敏规则（v1 冻结）。
 *
 * 脱敏是内建能力，不是业务责任：日志输出前统一脱敏，业务代码永远不直接脱敏。
 *
 * v1 冻结键名（实现层在序列化/写出前对下列键做脱敏，如 *** 或 [REDACTED]）：
 * - header: Authorization, Cookie
 * - query:  token, password
 * - body:   v1 可选不做；若做则 password, token, secret 等
 */
object SensitiveFilter {
    /** header 中需脱敏的键名（小写匹配） */
    val headerKeys: Set<String> = setOf("authorization", "cookie")

    /** query/body 等需脱敏的键名（小写匹配） */
    val paramKeys: Set<String> = setOf("token", "password", "secret")
}
