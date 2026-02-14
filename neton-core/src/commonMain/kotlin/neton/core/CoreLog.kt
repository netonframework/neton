package neton.core

import neton.logging.Logger
/**
 * neton-core 模块内 Logger 注入点。
 * 在 startSyncWithInstalls 中从 ctx 设置；早于 ctx 的路径通过 ensureBootstrap() 使用进程级 Logger。
 */
internal object CoreLog {
    var log: Logger? = null

    /** 早于 NetonContext 的路径（如 run() 入口）使用；保证不依赖 ctx 也能打日志 */
    fun ensureBootstrap(): Logger {
        if (log == null) {
            log = neton.logging.defaultLoggerFactory().get("neton.core")
        }
        return log!!
    }

    /** 返回非空 Logger（无 ctx 时使用进程级 bootstrap） */
    fun logOrBootstrap(): Logger = log ?: ensureBootstrap()
}
