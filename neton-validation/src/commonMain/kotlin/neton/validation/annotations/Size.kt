package neton.validation.annotations

/**
 * 集合/字符串长度范围
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Size(
    val min: Long = 0,
    val max: Long = Long.MAX_VALUE,
    val message: String = "size must be between {min} and {max}"
)
