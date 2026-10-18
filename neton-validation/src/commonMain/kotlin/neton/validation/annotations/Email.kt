package neton.validation.annotations

/**
 * 邮箱格式校验。缺省字段码 `INVALID_EMAIL`
 *
 * `message`：留空用框架内建字段码（`neton.core.http.ValidationCode`）；要给本字段专属的码时写
 * 业务机器码（`UPPER_SNAKE_CASE`，如 `INVALID_MOBILE`），KSP 编译期拒绝句子。字段错误的
 * `code` 与 `message` 都是这个码，客户端按 `code` 翻译。
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Email(val message: String = "")
