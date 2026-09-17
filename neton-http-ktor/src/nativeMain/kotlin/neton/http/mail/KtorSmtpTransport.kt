package neton.http.mail

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 最小 SMTP 客户端：EHLO → [STARTTLS] → AUTH → MAIL FROM → RCPT TO → DATA → QUIT。
 * TLS 用 ktor-network-tls（Kotlin 实现，Native 可用）；证书按系统信任链校验。
 * 消息体统一 base64（不用做点填充，中文主题走 RFC 2047 编码）。整次发信 60 秒超时。
 */
@OptIn(ExperimentalEncodingApi::class)
class KtorSmtpTransport(private val heloName: String = "nanogate") : SmtpTransport {

    override suspend fun send(account: SmtpAccount, message: SmtpMessage) = withTimeout(60_000) {
        val selector = SelectorManager(Dispatchers.Default)
        var socket: Socket = aSocket(selector).tcp().connect(account.host, account.port)
        try {
            if (account.ssl) socket = socket.tls(coroutineContext) { serverName = account.host }
            var input = socket.openReadChannel()
            var output = socket.openWriteChannel(autoFlush = true)
            expect(input, 220, context = "greeting")
            var ehlo = command(input, output, "EHLO $heloName", 250)
            if (!account.ssl && account.starttls) {
                command(input, output, "STARTTLS", 220)
                socket = socket.tls(coroutineContext) { serverName = account.host }
                input = socket.openReadChannel(); output = socket.openWriteChannel(autoFlush = true)
                ehlo = command(input, output, "EHLO $heloName", 250)
            }
            if (account.username.isNotBlank()) {
                val nul = Char(0).toString()
                if (ehlo.contains("AUTH") && ehlo.contains("PLAIN") && !ehlo.contains("LOGIN")) {
                    val token = Base64.encode((nul + account.username + nul + account.password).encodeToByteArray())
                    command(input, output, "AUTH PLAIN $token", 235)
                } else {
                    command(input, output, "AUTH LOGIN", 334)
                    command(input, output, Base64.encode(account.username.encodeToByteArray()), 334)
                    command(input, output, Base64.encode(account.password.encodeToByteArray()), 235)
                }
            }
            command(input, output, "MAIL FROM:<${message.from}>", 250)
            for (rcpt in message.to) command(input, output, "RCPT TO:<$rcpt>", 250, 251)
            command(input, output, "DATA", 354)
            output.writeStringUtf8(mime(message) + "\r\n.\r\n")
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

    /** 读一条（可能多行的）应答；返回全文；状态码不在 [ok] 里就抛，错误信息带服务器原话。 */
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

    private fun mime(m: SmtpMessage): String {
        val name = m.fromName
        val from = if (!name.isNullOrBlank()) "${encodeWord(name)} <${m.from}>" else m.from
        val body = Base64.encode(m.body.encodeToByteArray()).chunked(76).joinToString("\r\n")
        return buildString {
            append("From: ").append(from).append("\r\n")
            append("To: ").append(m.to.joinToString(", ")).append("\r\n")
            append("Subject: ").append(encodeWord(m.subject)).append("\r\n")
            append("Date: ").append(rfc2822Now()).append("\r\n")
            append("Message-ID: <").append(kotlin.random.Random.nextLong().toString(16)).append('.').append(kotlin.time.Clock.System.now().toEpochMilliseconds()).append('@').append(m.from.substringAfter('@', "localhost")).append(">\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: ").append(if (m.html) "text/html" else "text/plain").append("; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(body)
        }
    }

    /** RFC 2047：非 ASCII 才编码，纯 ASCII 原样。 */
    private fun encodeWord(s: String): String =
        if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?${Base64.encode(s.encodeToByteArray())}?="

    private fun rfc2822Now(): String {
        val secs = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000
        var days = secs / 86400; var rem = (secs % 86400).toInt(); if (rem < 0) { rem += 86400; days -= 1 }
        val z = days + 719468; val era = (if (z >= 0) z else z - 146096) / 146097; val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1; val m = if (mp < 10) mp + 3 else mp - 9; if (m <= 2) y += 1
        val dow = ((days % 7 + 10) % 7).toInt()  // 1970-01-01 是周四：index 3 in Mon-first list
        val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"); val mons = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        fun p2(n: Long) = n.toString().padStart(2, '0')
        return "${dows[dow]}, ${p2(d)} ${mons[(m - 1).toInt()]} $y ${p2((rem / 3600).toLong())}:${p2(((rem % 3600) / 60).toLong())}:${p2((rem % 60).toLong())} +0000"
    }
}

class SmtpException(message: String) : RuntimeException(message)
