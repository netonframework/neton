package neton.validation.annotations

/**
 * 字符串不得为 null 或空白
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class NotBlank(val message: String = "must not be blank")
