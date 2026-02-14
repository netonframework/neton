package neton.core.annotations

/**
 * HTTP 方法注解
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Get(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Post(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Put(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Delete(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Patch(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Head(val value: String = "")

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Options(val value: String = "")

/**
 * 核心参数绑定注解（精简化设计）
 */

/**
 * 路径参数绑定
 * 支持两种用法：
 * 1. @PathVariable("id") userId: Long  // 显式指定名称
 * 2. @PathVariable id: Long           // 隐式使用参数名
 * 
 * @param value 路径参数名称，如果为空则使用参数名
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PathVariable(val value: String = "")

/**
 * 请求体绑定（JSON）
 * 自动将 JSON 请求体反序列化为指定类型的对象
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Body

/**
 * 注意：AuthenticationPrincipal 注解已在 SecurityAnnotations.kt 中定义
 */

/**
 * 传统参数绑定注解（可选支持，通过 HttpRequest 手动获取更灵活）
 */

/**
 * 查询参数绑定 (建议使用 HttpRequest.queryParams 手动获取)
 * @param value 参数名称
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryParam(val value: String)

/**
 * 查询参数短名（规范 v1.0.1）
 * value 为空时使用参数名作为 query key
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String = "")

/**
 * 表单参数绑定
 * @param value 参数名称
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class FormParam(val value: String)

/**
 * 请求头绑定 (建议使用 HttpRequest.headers 手动获取)
 * @param value 请求头名称
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Header(val value: String)

/**
 * Cookie 绑定 (建议使用 HttpRequest.cookies 手动获取)
 * @param value Cookie 名称
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Cookie(val value: String) 