package neton.logging

/**
 * 唯一 Logger API（v1 硬约束冻结）。
 *
 * 所有 neton-* 模块只能依赖此接口；实现在本模块 neton.logging.internal，neton-core 只做 bind。禁止 println / println-like。
 *
 * 规则摘要：
 * - 结构化：业务数据放 [fields]，不拼进 [msg]
 * - error 级别必须传 [cause]；warn 可选；info/debug 不提供 cause
 */
interface Logger {
    /**
     * 该等级是否会真的输出。
     *
     * 调用方在**构造 fields 之前**问一次：fields 这个 map、里面的每个 Pair、每次数字装箱，
     * 都是在进入 log 之前就付掉的；等级过滤发生在实现内部，被丢掉的那行日志把这些全白付了。
     * 请求热路径（如 access log）每请求一次，这笔开销是可测量的。
     *
     * 默认 true：实现方不覆写也是正确的（只是拿不到这项优化）。
     */
    fun isEnabled(level: LogLevel): Boolean = true

    fun trace(msg: String, fields: Fields = emptyFields())
    fun debug(msg: String, fields: Fields = emptyFields())
    fun info(msg: String, fields: Fields = emptyFields())
    fun warn(msg: String, fields: Fields = emptyFields(), cause: Throwable? = null)
    fun error(msg: String, fields: Fields = emptyFields(), cause: Throwable? = null)
}
