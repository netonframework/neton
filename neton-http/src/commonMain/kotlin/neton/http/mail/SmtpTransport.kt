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
 * SMTP 发信端口。模块只依赖这个接口，由应用装配时 bind：
 * `bind(SmtpTransport::class, defaultSmtpTransport())`。
 *
 * 默认实现 [defaultSmtpTransport] 由 neton-http 自带，跑在 Neton 自己的网络栈上（netonstream io + tls，协程化，
 * 不依赖 Ktor / libcurl）：465 隐式 TLS、587 STARTTLS（服务器不支持就报错，绝不退回明文）、证书按系统信任链 +
 * SNI 校验。
 */
interface SmtpTransport {
    /** 发送一封邮件；任何失败（连接、TLS、认证、服务器拒收、超时）都抛 [SmtpException]。 */
    suspend fun send(account: SmtpAccount, message: SmtpMessage)
}

/** SMTP 发信失败。消息里有服务器的应答，但从不包含密码（明文或编码后的）。 */
class SmtpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Neton 内置的 SMTP 实现（netonstream io + tls）：连接 15 秒超时，整次发送 60 秒超时。
 * 无状态，可以全进程共用一个。
 */
expect fun defaultSmtpTransport(): SmtpTransport
