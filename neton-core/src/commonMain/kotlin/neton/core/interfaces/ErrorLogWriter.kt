package neton.core.interfaces

/**
 * API 错误日志写入器接口
 *
 * 由 KtorHttpAdapter 在请求发生 500 异常时调用（异步），
 * 业务模块实现此接口将错误日志持久化到数据库。
 */
interface ErrorLogWriter {

    suspend fun write(entry: ErrorLogEntry)
}

data class ErrorLogEntry(
    val userId: Long?,
    val userType: Int,
    val applicationName: String,
    val requestMethod: String,
    val requestUrl: String,
    val requestParams: String?,
    val userIp: String,
    val userAgent: String?,
    val exceptionName: String,
    val exceptionMessage: String?,
    val exceptionStackTrace: String?
)
