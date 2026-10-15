package neton.http.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import neton.http.engine.default.client.ClientReactor
import neton.http.engine.default.client.ConnectTimeoutException
import neton.http.engine.default.client.connectTcp
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.openssl.TlsContext
import neton.tls.peerIdentityOf
import neton.tls.systemTrustRootsPem
import neton.tls.tlsConnect
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual fun defaultSmtpTransport(): SmtpTransport = NetonStreamSmtpTransport()

/** System trust roots, no ALPN: shared by every SMTP send of the process. */
private val defaultSmtpTls: TlsContext by lazy { TlsContext(server = false, trustRootsPem = systemTrustRootsPem()) }

/**
 * SMTP client (RFC 5321) on Neton's own network stack: netonstream io for TCP, netonstream tls for TLS.
 *
 * - [SmtpAccount.ssl]: implicit TLS from the first byte (submissions, port 465, RFC 8314).
 * - [SmtpAccount.starttls]: plaintext greeting + EHLO, then `STARTTLS` (RFC 3207) and EHLO again over
 *   TLS. A server that does not offer STARTTLS fails the send: it never falls back to plaintext.
 * - Neither: plaintext (internal relays and tests only).
 *
 * TLS verifies the server certificate against [tls] (the system trust roots by default) with SNI and
 * host name / IP identity of [SmtpAccount.host]. Authentication (when the username is not blank) uses
 * AUTH PLAIN when the server offers it, else AUTH LOGIN. All recipients get a `RCPT TO`; any one of
 * them refused fails the send. The message (from [MimeBuilder]) goes out with CRLF line endings and
 * dot-stuffing.
 *
 * Each send runs on a reactor thread of its own (started for the send, stopped after it), so callers
 * may be on any thread. Timeouts: [connectTimeoutMillis] for TCP connect and each TLS handshake,
 * [sendTimeoutMillis] for the whole send.
 */
internal class NetonStreamSmtpTransport(
    private val tls: TlsContext = defaultSmtpTls,
    private val heloName: String = "localhost",
    private val connectTimeoutMillis: Long = 15_000,
    private val sendTimeoutMillis: Long = 60_000,
) : SmtpTransport {

    override suspend fun send(account: SmtpAccount, message: SmtpMessage) {
        validate(account, message)
        val data = SmtpData.encode(MimeBuilder.build(message))
        val target = "${account.host}:${account.port}"
        val reactor = ClientReactor(
            name = "neton-smtp",
            setUp = { },
            tearDown = { },
            closedError = { SmtpException("SMTP send to $target was interrupted: the transport stopped", it) },
            startError = { SmtpException("SMTP transport failed to start: ${it.message ?: it}", it) },
        )
        try {
            reactor.run {
                try {
                    withTimeout(sendTimeoutMillis) { session(account, message, data) }
                } catch (e: TimeoutCancellationException) {
                    throw SmtpException("SMTP send to $target timed out after ${sendTimeoutMillis}ms", e)
                } catch (e: ConnectTimeoutException) {
                    throw SmtpException("SMTP connect to $target timed out after ${connectTimeoutMillis}ms", e)
                } catch (e: SmtpException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IoException) {
                    throw SmtpException("SMTP connection to $target failed: ${e.message ?: e}", e)
                }
            }
        } finally {
            withContext(NonCancellable) { reactor.stop() }
        }
    }

    private suspend fun session(account: SmtpAccount, message: SmtpMessage, data: ByteArray) {
        val plain = connectTcp(account.host, account.port, connectTimeoutMillis)
        var conn = SmtpConnection(
            if (account.ssl) handshake(plain, account.host) else plain,
        )
        try {
            conn.expect("greeting", 220)
            var capabilities = ehlo(conn)
            if (!account.ssl && account.starttls) {
                if (!capabilities.has("STARTTLS")) {
                    throw SmtpException("SMTP server ${account.host}:${account.port} does not offer STARTTLS; refusing to send in plaintext")
                }
                conn.command("STARTTLS", "STARTTLS", 220)
                // Anything the server sent past its 220 arrived before TLS: it must not be read as a
                // reply over the secured stream (RFC 3207 §5, response injection).
                if (conn.hasBufferedInput) throw SmtpException("SMTP server sent data after its STARTTLS reply; refusing to continue")
                conn = SmtpConnection(handshake(conn.stream, account.host))
                capabilities = ehlo(conn)
            }
            if (account.username.isNotBlank()) authenticate(conn, account, capabilities)
            conn.command("MAIL FROM:<${message.from}>", "MAIL FROM", 250)
            for (rcpt in message.to) conn.command("RCPT TO:<$rcpt>", "RCPT TO <$rcpt>", 250, 251)
            conn.command("DATA", "DATA", 354)
            conn.writeRaw(data)
            conn.expect("message data", 250)
            // The message is accepted; a failing QUIT does not undo that.
            try {
                conn.command("QUIT", "QUIT", 221)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        } finally {
            conn.stream.close()
        }
    }

    private suspend fun handshake(stream: IoStream, host: String): IoStream =
        try {
            // tlsConnect closes the transport when the handshake fails.
            tlsConnect(stream, tls, peerIdentityOf(host), handshakeTimeoutMillis = connectTimeoutMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SmtpException("SMTP TLS handshake with $host failed: ${e.message ?: e}", e)
        }

    private suspend fun ehlo(conn: SmtpConnection): Capabilities =
        Capabilities.parse(conn.command("EHLO $heloName", "EHLO", 250))

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun authenticate(conn: SmtpConnection, account: SmtpAccount, capabilities: Capabilities) {
        val mechanisms = capabilities.authMechanisms
        when {
            "PLAIN" in mechanisms -> {
                val nul = Char(0).toString()
                val token = Base64.encode((nul + account.username + nul + account.password).encodeToByteArray())
                conn.command("AUTH PLAIN $token", "AUTH PLAIN", 235)
            }
            "LOGIN" in mechanisms -> {
                conn.command("AUTH LOGIN", "AUTH LOGIN", 334)
                conn.command(Base64.encode(account.username.encodeToByteArray()), "AUTH LOGIN username", 334)
                conn.command(Base64.encode(account.password.encodeToByteArray()), "AUTH LOGIN password", 235)
            }
            mechanisms.isEmpty() -> throw SmtpException("SMTP server ${account.host} does not offer AUTH, but a username is configured")
            else -> throw SmtpException(
                "SMTP server ${account.host} offers no supported AUTH mechanism (offered: ${mechanisms.joinToString(" ")}; supported: PLAIN LOGIN)",
            )
        }
    }

    private fun validate(account: SmtpAccount, message: SmtpMessage) {
        if (account.host.isBlank()) throw SmtpException("SMTP host is empty")
        if (account.port !in 1..65535) throw SmtpException("SMTP port out of range: ${account.port}")
        if (account.ssl && account.starttls) throw SmtpException("SMTP account enables both ssl (implicit TLS) and starttls; choose one")
        if (message.to.isEmpty()) throw SmtpException("SMTP message has no recipients")
        // Addresses go into commands and headers verbatim: a CR, LF or angle bracket would inject.
        for (address in listOf(message.from) + message.to) {
            if (address.isBlank() || address.any { it == '\r' || it == '\n' || it == '<' || it == '>' || it.code < 32 }) {
                throw SmtpException("invalid mail address: ${address.filter { it.code >= 32 }}")
            }
        }
        if (account.username.any { it == '\r' || it == '\n' }) throw SmtpException("SMTP username contains a line break")
    }
}

/** One SMTP reply: the code and the text of each line (without the code and separator). */
internal class SmtpReply(val code: Int, val lines: List<String>) {
    override fun toString(): String = lines.joinToString(" | ") { "$code $it".trimEnd() }
}

/** EHLO keywords (upper case) and the AUTH mechanisms. */
internal class Capabilities(private val keywords: Map<String, List<String>>) {
    fun has(keyword: String): Boolean = keyword in keywords

    val authMechanisms: List<String> get() = keywords["AUTH"].orEmpty()

    companion object {
        fun parse(reply: SmtpReply): Capabilities {
            val map = LinkedHashMap<String, List<String>>()
            // The first line is the server's domain and greeting, the rest are keywords.
            for (line in reply.lines.drop(1)) {
                val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }.map { it.uppercase() }
                if (parts.isEmpty()) continue
                // Old servers also advertise `AUTH=LOGIN PLAIN`.
                val head = parts[0]
                if (head.startsWith("AUTH=")) {
                    map["AUTH"] = (map["AUTH"].orEmpty() + head.removePrefix("AUTH=") + parts.drop(1)).distinct()
                } else if (head == "AUTH") {
                    map["AUTH"] = (map["AUTH"].orEmpty() + parts.drop(1)).distinct()
                } else {
                    map[head] = parts.drop(1)
                }
            }
            return Capabilities(map)
        }
    }
}

/** The DATA payload: CRLF line endings, leading dots doubled, the `CRLF.CRLF` terminator appended. */
internal object SmtpData {
    fun encode(message: String): ByteArray {
        val out = StringBuilder(message.length + 64)
        var atLineStart = true
        var i = 0
        while (i < message.length) {
            val c = message[i]
            when (c) {
                '\r' -> {
                    out.append("\r\n")
                    if (i + 1 < message.length && message[i + 1] == '\n') i++
                    atLineStart = true
                }
                '\n' -> {
                    out.append("\r\n")
                    atLineStart = true
                }
                else -> {
                    if (atLineStart && c == '.') out.append('.')
                    out.append(c)
                    atLineStart = false
                }
            }
            i++
        }
        if (!atLineStart) out.append("\r\n")
        out.append(".\r\n")
        return out.toString().encodeToByteArray()
    }
}

/** Reads replies and writes commands on one stream (plain or TLS). Runs on the reactor thread. */
internal class SmtpConnection(val stream: IoStream) {
    private val readBuffer = Buffer(4096)
    private var pending = ByteArray(0)

    /** Bytes received but not yet consumed as part of a reply. */
    val hasBufferedInput: Boolean get() = pending.isNotEmpty()

    /** Sends [line] and expects one of [ok]. [label] names the command in errors (never its arguments). */
    suspend fun command(line: String, label: String, vararg ok: Int): SmtpReply {
        writeRaw((line + "\r\n").encodeToByteArray())
        return expect(label, *ok)
    }

    suspend fun writeRaw(bytes: ByteArray) {
        val out = Buffer(bytes.size)
        out.writeBytes(bytes)
        while (!out.isEmpty) stream.write(out)
        stream.flush()
    }

    suspend fun expect(label: String, vararg ok: Int): SmtpReply {
        val reply = readReply(label)
        if (reply.code !in ok) throw SmtpException("SMTP $label failed: $reply")
        return reply
    }

    private suspend fun readReply(label: String): SmtpReply {
        val lines = ArrayList<String>()
        var code = -1
        var total = 0
        while (true) {
            val line = readLine(label)
            total += line.length
            if (lines.size >= MAX_REPLY_LINES || total > MAX_REPLY_BYTES) throw SmtpException("SMTP reply to $label is too long")
            val lineCode = line.take(3).takeIf { it.length == 3 && it.all(Char::isDigit) }?.toInt()
            val separator = line.getOrNull(3)
            if (lineCode == null || (separator != null && separator != ' ' && separator != '-')) {
                throw SmtpException("malformed SMTP reply to $label: ${line.take(200)}")
            }
            if (code != -1 && lineCode != code) throw SmtpException("malformed SMTP reply to $label: mixed reply codes $code and $lineCode")
            code = lineCode
            lines += if (line.length > 4) line.substring(4) else ""
            if (separator != '-') return SmtpReply(code, lines)
        }
    }

    private suspend fun readLine(label: String): String {
        while (true) {
            val lf = pending.indexOf('\n'.code.toByte())
            if (lf >= 0) {
                val end = if (lf > 0 && pending[lf - 1] == '\r'.code.toByte()) lf - 1 else lf
                val line = pending.decodeToString(0, end)
                pending = pending.copyOfRange(lf + 1, pending.size)
                return line
            }
            if (pending.size > MAX_LINE_BYTES) throw SmtpException("SMTP reply line to $label is too long")
            if (stream.read(readBuffer) < 0) throw SmtpException("SMTP server closed the connection during $label")
            pending += readBuffer.readAll()
        }
    }

    private companion object {
        const val MAX_LINE_BYTES = 4096
        const val MAX_REPLY_LINES = 512
        const val MAX_REPLY_BYTES = 64 * 1024
    }
}
