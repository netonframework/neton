package neton.validation.annotations

/**
 * 数值/长度上限
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Max(val value: Long, val message: String = "must be <= {value}")
