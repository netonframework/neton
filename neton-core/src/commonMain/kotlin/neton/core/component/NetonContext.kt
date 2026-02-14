package neton.core.component

import kotlin.reflect.KClass

/**
 * 唯一容器 - 启动期 + 运行期共用。
 * Core 不持有 port/httpConfig 等业务语义，仅 bind/get。
 */
class NetonContext(val args: Array<String>) {
    private val registry = mutableMapOf<KClass<*>, Any>()

    /** 绑定实例（按实现类型） */
    fun <T : Any> bind(impl: T) {
        @Suppress("UNCHECKED_CAST")
        registry[impl::class as KClass<T>] = impl
    }

    /** 绑定实例（按接口类型） */
    fun <T : Any> bind(type: KClass<T>, impl: T) {
        registry[type] = impl
    }

    /** 按需绑定，已存在则不覆盖，返回 true 表示成功绑定 */
    fun <T : Any> bindIfAbsent(type: KClass<T>, impl: T): Boolean {
        if (type in registry) return false
        registry[type] = impl
        return true
    }

    /** 获取绑定 */
    fun <T : Any> get(type: KClass<T>): T {
        return registry[type] as? T
            ?: throw IllegalStateException("No binding for ${type.simpleName}. Did you install the component?")
    }

    /** inline 泛型：ctx.get<UserRepository>() */
    inline fun <reified T : Any> get(): T = get(T::class)

    /** 安全获取 */
    fun <T : Any> getOrNull(type: KClass<T>): T? = registry[type] as? T

    inline fun <reified T : Any> getOrNull(): T? = getOrNull(T::class)

    companion object {
        private var _current: NetonContext? = null

        fun current(): NetonContext = _current
            ?: throw IllegalStateException("NetonContext not initialized. Call from Neton.run { } scope.")

        internal fun setCurrent(ctx: NetonContext?) { _current = ctx }
    }
}
