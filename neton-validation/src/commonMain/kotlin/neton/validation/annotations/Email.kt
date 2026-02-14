package neton.validation.annotations

/**
 * 邮箱格式校验
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Email(val message: String = "invalid email")
