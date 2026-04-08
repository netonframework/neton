package neton.core.interfaces

/**
 * API 访问日志写入器接口
 *
 * 由 KtorHttpAdapter 在每次请求完成后调用（异步），
 * 业务模块实现此接口将日志持久化到数据库。
 */
interface AccessLogWriter {

    /**
     * 写入一条 API 访问日志
     */
    suspend fun write(entry: AccessLogEntry)
}

/**
 * API 访问日志条目
 */
data class AccessLogEntry(
    val userId: Long?,
    val userType: Int,
    val applicationName: String,
    val requestMethod: String,
    val requestUrl: String,
    val requestParams: String?,
    val userIp: String,
    val userAgent: String?,
    val beginTime: Long,
    val endTime: Long,
    val duration: Long,
    val resultCode: Int,
    val resultMsg: String?
)
