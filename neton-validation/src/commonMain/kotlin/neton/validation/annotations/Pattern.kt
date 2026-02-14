package neton.validation.annotations

/**
 * 字符串必须匹配正则
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Pattern(val regex: String, val message: String = "must match pattern")
