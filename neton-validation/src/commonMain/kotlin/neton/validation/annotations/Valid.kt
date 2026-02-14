package neton.validation.annotations

/**
 * 嵌套对象需递归校验（预留，后续支持嵌套 DTO）
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Valid
