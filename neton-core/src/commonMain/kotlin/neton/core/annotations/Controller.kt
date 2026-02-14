package neton.core.annotations

/**
 * 标记一个类为控制器
 * 用于路由系统自动扫描和注册
 * @param path 控制器的基础路径，如 "/api/users" 或 "/"
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Controller(val path: String = "") 