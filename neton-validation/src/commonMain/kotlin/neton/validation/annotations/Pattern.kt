package neton.validation.annotations

/**
 * 字符串必须匹配正则。缺省字段码 `INVALID_FORMAT`
 *
 * `message`：留空用框架内建字段码（`neton.core.http.ValidationCode`）；要给本字段专属的码时写
 * 业务机器码（`UPPER_SNAKE_CASE`，如 `INVALID_MOBILE`），KSP 编译期拒绝句子。字段错误的
 * `code` 与 `message` 都是这个码，客户端按 `code` 翻译。
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Pattern(val regex: String, val message: String = "")
