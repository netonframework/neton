package neton.core.annotations

/**
 * 标记仅供 Neton 内部模块使用的 API。
 *
 * 这些声明之所以是 public，只是因为它们要跨模块边界被框架自身调用，
 * 并不构成对外契约：签名、语义、存在性都可能在任意版本变更，不受兼容性承诺保护。
 *
 * 应用代码不应使用。框架内部调用点请显式 `@OptIn(InternalNetonApi::class)`。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal Neton API, not part of the public contract, and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalNetonApi
