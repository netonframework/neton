package neton.ksp

/**
 * 机器码的形状（与 neton-core `MACHINE_CODE_REGEX` 一致）：`UPPER_SNAKE_CASE`，字母开头。
 *
 * 注解上的 `message`（`@RateLimit`、`@NotBlank`/`@Size`/`@Pattern`…）原样进响应信封，
 * 客户端拿它查语言包，所以编译期就拒绝句子。
 */
internal val MACHINE_CODE: Regex = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
