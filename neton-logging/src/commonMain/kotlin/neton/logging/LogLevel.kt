package neton.logging

/**
 * 日志等级（v1 冻结）。
 * 与 [Logger] 接口配合；实现层用于过滤与采样。
 */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
