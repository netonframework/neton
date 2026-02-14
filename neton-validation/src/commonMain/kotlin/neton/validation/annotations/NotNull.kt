package neton.validation.annotations

/**
 * 字段不得为 null（用于可空类型校验）
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class NotNull(val message: String = "must not be null")
