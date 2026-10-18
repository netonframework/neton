package neton.validation.annotations

/**
 * 集合/字符串长度范围。缺省字段码：字符串 `TOO_SHORT`/`TOO_LONG`，集合/数组 `TOO_FEW_ITEMS`/`TOO_MANY_ITEMS`；params `{"min","max"}`
 *
 * `message`：留空用框架内建字段码（`neton.core.http.ValidationCode`）；要给本字段专属的码时写
 * 业务机器码（`UPPER_SNAKE_CASE`，如 `INVALID_MOBILE`），KSP 编译期拒绝句子。字段错误的
 * `code` 与 `message` 都是这个码，客户端按 `code` 翻译。
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Size(
    val min: Long = 0,
    val max: Long = Long.MAX_VALUE,
    val message: String = ""
)
