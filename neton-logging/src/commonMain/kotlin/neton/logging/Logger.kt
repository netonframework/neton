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
    fun trace(msg: String, fields: Fields = emptyFields())
    fun debug(msg: String, fields: Fields = emptyFields())
    fun info(msg: String, fields: Fields = emptyFields())
    fun warn(msg: String, fields: Fields = emptyFields(), cause: Throwable? = null)
    fun error(msg: String, fields: Fields = emptyFields(), cause: Throwable? = null)
}
