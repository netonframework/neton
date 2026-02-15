package neton.validation.annotations

/**
 * 嵌套对象递归校验，KSP 会为嵌套类型生成 Validator 并自动调用，错误路径自动加前缀。
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Valid
