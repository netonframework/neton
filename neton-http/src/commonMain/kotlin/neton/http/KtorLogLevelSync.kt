package neton.http

/**
 * 将 KTOR_LOG_LEVEL 与框架 [logging] level 同步。
 * 若用户未显式设置 KTOR_LOG_LEVEL，则根据 application.conf 的 logging.level 设置。
 * Native 下 Ktor 读取该环境变量控制内部日志输出。
 */
internal expect fun syncKtorLogLevelToConfig(level: String)
