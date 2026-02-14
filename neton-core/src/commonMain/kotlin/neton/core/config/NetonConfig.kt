package neton.core.config

import neton.core.component.NetonContext
import neton.core.interfaces.SecurityBuilder

/**
 * 通用配置器接口 - 一次设计，各组件复用
 *
 * 业务层通过实现此接口 + @NetonConfig(component) 参与配置，无需写进 DSL。
 * ctx 使 Configurer 能访问其他 Service、配置、生命周期（对标 Spring BeanPostProcessor、Ktor Plugin）。
 * order 控制执行顺序，小值先执行；需要非零顺序时 override。
 */
interface NetonConfigurer<T : Any> {

    val order: Int
        get() = 0

    fun configure(ctx: NetonContext, target: T)
}

/**
 * 注解 order 转运行时的工厂 - 由 KSP 生成代码使用，注解为唯一 order 来源
 */
object NetonConfigurers {
    fun <T : Any> ordered(orderValue: Int, configurer: NetonConfigurer<T>): NetonConfigurer<T> =
        object : NetonConfigurer<T> {
            override val order: Int get() = orderValue
            override fun configure(ctx: NetonContext, target: T) = configurer.configure(ctx, target)
        }
}

/**
 * 通用配置注解 - 按 component 分组，KSP 自动发现
 *
 * order: 执行顺序，小值先执行（默认 0），由 KSP 注入运行时，用户无需 override
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NetonConfig(
    val component: String,
    val order: Int = 0
)

/**
 * 编译期生成的配置注册表接口 - 由 KSP 生成实现
 */
interface NetonConfigRegistry {
    val securityConfigurers: List<NetonConfigurer<SecurityBuilder>>
}

/** Security 配置器类型别名 - 业务层可写 class X : SecurityConfigurer */
typealias SecurityConfigurer = NetonConfigurer<SecurityBuilder>

/**
 * 空实现 - 未使用 @NetonConfig 时无需传入 registry
 */
object EmptyNetonConfigRegistry : NetonConfigRegistry {
    override val securityConfigurers: List<NetonConfigurer<SecurityBuilder>> = emptyList()
}
