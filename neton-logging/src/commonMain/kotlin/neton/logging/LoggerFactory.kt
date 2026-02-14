package neton.logging

import kotlin.reflect.KClass

/**
 * 唯一入口：业务与 neton-* 模块通过此获取 Logger，禁止直接 new JsonLogger()。
 *
 * 冻结 API：
 * - get(name: String): Logger
 * - get(clazz): Logger 等价于 get(clazz.qualifiedName ?: "unknown")
 *
 * 启动时由 neton-core bind 一次，例如：
 * ctx.bindIfAbsent(LoggerFactory::class, defaultLoggerFactory())
 */
interface LoggerFactory {
    fun get(name: String): Logger
    fun get(clazz: KClass<*>): Logger = get(clazz.qualifiedName ?: "unknown")
}
