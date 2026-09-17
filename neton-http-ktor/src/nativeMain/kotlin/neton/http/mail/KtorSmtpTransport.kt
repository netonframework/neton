package neton.http.mail

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 纯 Kotlin socket 的 SMTP 客户端，**只支持明文**（Ktor 的 socket 在 Native 上没有 TLS：`Socket.tls()` 直接抛
 * "TLS sessions are not supported on Native platform"）。POSIX 平台默认用 [CurlSmtpTransport]；
 * 这个实现留给没有系统 libcurl 的 Windows 与内网明文中继。
 */
@OptIn(ExperimentalEncodingApi::class)
class KtorSmtpTransport(private val heloName: String = "nanogate") : SmtpTransport {

    override suspend fun send(account: SmtpAccount, message: SmtpMessage) = withTimeout(60_000) {
        if (account.ssl || account.starttls) throw SmtpException("此平台的 SMTP 只支持明文连接（没有系统 libcurl）；请关闭 SSL/STARTTLS 或改用厂商 HTTP 接口")
        val selector = SelectorManager(Dispatchers.Default)
        val socket: Socket = aSocket(selector).tcp().connect(account.host, account.port)
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            expect(input, 220, context = "greeting")
            val ehlo = command(input, output, "EHLO $heloName", 250)
            if (account.username.isNotBlank()) {
                val nul = Char(0).toString()
                if (ehlo.contains("AUTH") && ehlo.contains("PLAIN") && !ehlo.contains("LOGIN")) {
                    command(input, output, "AUTH PLAIN " + Base64.encode((nul + account.username + nul + account.password).encodeToByteArray()), 235)
                } else {
                    command(input, output, "AUTH LOGIN", 334)
                    command(input, output, Base64.encode(account.username.encodeToByteArray()), 334)
                    command(input, output, Base64.encode(account.password.encodeToByteArray()), 235)
                }
            }
            command(input, output, "MAIL FROM:<${message.from}>", 250)
            for (rcpt in message.to) command(input, output, "RCPT TO:<$rcpt>", 250, 251)
            command(input, output, "DATA", 354)
            output.writeStringUtf8(MimeBuilder.build(message) + "\r\n.\r\n")
            expect(input, 250, context = "DATA")
            runCatching { command(input, output, "QUIT", 221) }
            Unit
        } finally {
            runCatching { socket.close() }
            runCatching { selector.close() }
        }
    }

    private suspend fun command(input: ByteReadChannel, output: ByteWriteChannel, line: String, vararg ok: Int): String {
        output.writeStringUtf8(line + "\r\n")
        return expect(input, *ok, context = line.substringBefore(' '))
    }

    private suspend fun expect(input: ByteReadChannel, vararg ok: Int, context: String): String {
        val sb = StringBuilder()
        var code: Int
        while (true) {
            val line = input.readUTF8Line() ?: throw SmtpException("connection closed during $context")
            sb.append(line).append('\n')
            code = line.take(3).toIntOrNull() ?: throw SmtpException("malformed SMTP reply during $context: $line")
            if (line.length < 4 || line[3] != '-') break
        }
        if (code !in ok) throw SmtpException("SMTP $context failed: ${sb.toString().trim()}")
        return sb.toString()
    }
}
