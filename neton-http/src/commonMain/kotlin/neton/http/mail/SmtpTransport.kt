package neton.http.mail

/** SMTP 账号：465 用隐式 TLS（[ssl]），587 用 STARTTLS（[starttls]），两者都关就是明文（只适合内网中继与测试）。 */
data class SmtpAccount(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ssl: Boolean,
    val starttls: Boolean,
)

data class SmtpMessage(
    val from: String,
    val fromName: String? = null,
    val to: List<String>,
    val subject: String,
    val body: String,
    val html: Boolean,
)

/**
 * SMTP 发信端口。契约层只定义接口，实现（socket + TLS）在引擎适配层（neton-http-ktor），
 * 由应用装配时 bind —— 与 HttpClientProvider 同一套做法：模块不带引擎。
 */
interface SmtpTransport {
    suspend fun send(account: SmtpAccount, message: SmtpMessage)
}
