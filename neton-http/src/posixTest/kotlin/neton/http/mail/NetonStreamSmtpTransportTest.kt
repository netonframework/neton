package neton.http.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neton.http.engine.default.TestIdentity
import neton.http.engine.default.ignoreSigpipe
import neton.http.engine.default.testIdentity
import neton.io.bytes.Buffer
import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.listen
import neton.io.net.runReactor
import neton.openssl.TlsContext
import neton.tls.tlsAccept
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class NetonStreamSmtpTransportTest {

    private val identity: TestIdentity by lazy { testIdentity() }

    /** Trusts the test certificate only (it is self-signed: it is its own root). */
    private fun transport() = NetonStreamSmtpTransport(tls = TlsContext(server = false, trustRootsPem = identity.certificate))

    private fun account(port: Int, ssl: Boolean = false, starttls: Boolean = false, password: String = PASSWORD) =
        SmtpAccount(host = "127.0.0.1", port = port, username = USER, password = password, ssl = ssl, starttls = starttls)

    private val message = SmtpMessage(
        from = "sender@example.test",
        fromName = "Neton 测试",
        to = listOf("a@example.test", "b@example.test", "c@example.test"),
        subject = "你好 SMTP",
        body = "line one\n.leading dot\r\n..two dots\nend",
        html = false,
    )

    @Test
    fun plaintextAuthPlainMultipleRecipientsAndMultiLineReplies() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(auth = listOf("LOGIN", "PLAIN")))
        try {
            withTimeout(20_000) { transport().send(account(server.port), message) }
            val s = server.sessions.receive()
            assertEquals("PLAIN", s.authMechanism)
            assertEquals(USER to PASSWORD, s.credentials)
            assertEquals("sender@example.test", s.mailFrom)
            assertEquals(message.to, s.recipients)
            assertFalse(s.secured)
            assertTrue(s.quit)
            assertBody(s.data!!)
        } finally {
            server.stop()
        }
    }

    @Test
    fun authLoginWhenThatIsAllTheServerOffers() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(auth = listOf("LOGIN")))
        try {
            withTimeout(20_000) { transport().send(account(server.port), message) }
            val s = server.sessions.receive()
            assertEquals("LOGIN", s.authMechanism)
            assertEquals(USER to PASSWORD, s.credentials)
            assertBody(s.data!!)
        } finally {
            server.stop()
        }
    }

    @Test
    fun blankUsernameSkipsAuth() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(auth = listOf("PLAIN")))
        try {
            withTimeout(20_000) { transport().send(account(server.port).copy(username = ""), message) }
            val s = server.sessions.receive()
            assertNull(s.authMechanism)
            assertEquals(message.to, s.recipients)
        } finally {
            server.stop()
        }
    }

    @Test
    fun starttlsUpgradesBeforeAuthAndSendsAgainEhlo() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(starttls = true, auth = listOf("PLAIN")))
        try {
            withTimeout(20_000) { transport().send(account(server.port, starttls = true), message) }
            val s = server.sessions.receive()
            assertTrue(s.secured, "MAIL FROM must arrive over TLS")
            assertTrue(s.authSecured, "credentials must only travel over TLS")
            assertEquals(2, s.ehloCount)
            assertBody(s.data!!)
        } finally {
            server.stop()
        }
    }

    @Test
    fun starttlsRequiredButNotOfferedFailsWithoutSendingAnything() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(starttls = false, auth = listOf("PLAIN")))
        try {
            val e = assertFailsWith<SmtpException> {
                withTimeout(20_000) { transport().send(account(server.port, starttls = true), message) }
            }
            assertTrue("STARTTLS" in e.message!!, e.message)
            val s = server.sessions.receive()
            assertNull(s.authMechanism)
            assertNull(s.mailFrom)
        } finally {
            server.stop()
        }
    }

    @Test
    fun implicitTls() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(implicitTls = true, auth = listOf("PLAIN", "LOGIN")))
        try {
            withTimeout(20_000) { transport().send(account(server.port, ssl = true), message) }
            val s = server.sessions.receive()
            assertTrue(s.secured)
            assertEquals(1, s.ehloCount)
            assertEquals(message.to, s.recipients)
            assertBody(s.data!!)
        } finally {
            server.stop()
        }
    }

    @Test
    fun implicitTlsRejectsACertificateTheSystemDoesNotTrust() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(implicitTls = true, auth = listOf("PLAIN")))
        try {
            val e = assertFailsWith<SmtpException> {
                withTimeout(20_000) { NetonStreamSmtpTransport().send(account(server.port, ssl = true), message) }
            }
            assertTrue("TLS handshake" in e.message!!, e.message)
        } finally {
            server.stop()
        }
    }

    @Test
    fun wrongPasswordFailsWithoutLeakingIt() = runBlocking {
        for (mechanism in listOf("PLAIN", "LOGIN")) {
            val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(auth = listOf(mechanism)))
            try {
                val wrong = "s3cret-Wrong!"
                val e = assertFailsWith<SmtpException> {
                    withTimeout(20_000) { transport().send(account(server.port, password = wrong), message) }
                }
                val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
                assertTrue("535" in text, text)
                assertTrue("AUTH $mechanism" in text, text)
                assertFalse(wrong in text, text)
                assertFalse(Base64.encode(wrong.encodeToByteArray()) in text, text)
                assertFalse(Base64.encode("\u0000$USER\u0000$wrong".encodeToByteArray()) in text, text)
                assertNull(server.sessions.receive().mailFrom)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun aRefusedRecipientFailsTheSend() = runBlocking {
        val server = FakeSmtpServer.start(identity, FakeSmtpServer.Options(auth = listOf("PLAIN"), refuse = setOf("b@example.test")))
        try {
            val e = assertFailsWith<SmtpException> {
                withTimeout(20_000) { transport().send(account(server.port), message) }
            }
            assertTrue("b@example.test" in e.message!! && "550" in e.message!!, e.message)
            assertNull(server.sessions.receive().data)
        } finally {
            server.stop()
        }
    }

    @Test
    fun addressesWithLineBreaksAreRefusedBeforeConnecting() = runBlocking {
        val e = assertFailsWith<SmtpException> {
            transport().send(account(1), message.copy(to = listOf("a@example.test>\r\nRCPT TO:<evil@example.test")))
        }
        assertTrue("invalid mail address" in e.message!!, e.message)
    }

    @Test
    fun dataIsDotStuffedWithCrlfLineEndings() {
        assertEquals(
            "a\r\n..b\r\n...c\r\nd\r\n\r\n..\r\n.\r\n",
            SmtpData.encode("a\n.b\r\n..c\rd\n\n.").decodeToString(),
        )
        assertEquals("x\r\n.\r\n", SmtpData.encode("x\r\n").decodeToString())
    }

    /** The server un-stuffed the data; the body is base64 per [MimeBuilder]. */
    private fun assertBody(data: String) {
        assertTrue("Subject: =?UTF-8?B?" in data, data)
        assertTrue("To: a@example.test, b@example.test, c@example.test" in data, data)
        val encoded = data.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals(message.body, Base64.decode(encoded).decodeToString())
    }

    private companion object {
        const val USER = "mailer@example.test"
        const val PASSWORD = "p@ss-Word-42"
    }
}

/**
 * An in-process SMTP server on a reactor thread of its own (netonstream io + tls): multi-line greeting
 * and EHLO, optional STARTTLS or implicit TLS with [TestIdentity], AUTH PLAIN / LOGIN checked against
 * the test credentials. Each connection's transcript lands in [sessions] when it ends.
 */
@OptIn(ObsoleteWorkersApi::class, ExperimentalEncodingApi::class)
internal class FakeSmtpServer private constructor(private val tls: TlsContext, private val options: Options) {

    class Options(
        val implicitTls: Boolean = false,
        val starttls: Boolean = false,
        val auth: List<String> = emptyList(),
        val refuse: Set<String> = emptySet(),
        val user: String = "mailer@example.test",
        val password: String = "p@ss-Word-42",
    )

    class Session {
        var ehloCount = 0
        var secured = false
        var authMechanism: String? = null
        var authSecured = false
        var credentials: Pair<String, String>? = null
        var mailFrom: String? = null
        val recipients = ArrayList<String>()
        var data: String? = null
        var quit = false
    }

    val sessions = Channel<Session>(Channel.UNLIMITED)
    private val bound = CompletableDeferred<Int>()
    private val stopRequested = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    private val worker = Worker.start(name = "test-smtp")
    var port: Int = 0
        private set

    private fun runOnThisThread() {
        try {
            runReactor {
                val listener = listen("127.0.0.1", 0)
                bound.complete(listener.localAddress.port)
                val accepting = launch { acceptLoop(listener) }
                stopRequested.await()
                listener.close()
                accepting.cancel()
                coroutineContext.cancelChildren()
            }
        } catch (e: Throwable) {
            bound.completeExceptionally(e)
        } finally {
            stopped.complete(Unit)
            worker.requestTermination(processScheduledJobs = false)
        }
    }

    private suspend fun CoroutineScope.acceptLoop(listener: TcpListener) {
        while (true) {
            val stream = try { listener.accept() } catch (e: Exception) { return }
            launch {
                val session = Session()
                var io: IoStream = stream
                try {
                    if (options.implicitTls) {
                        io = tlsAccept(stream, tls)
                        session.secured = true
                    }
                    serve(io, stream, session) { io = it }
                } catch (e: Exception) {
                    // The client went away (or failed TLS): the transcript so far is the result.
                } finally {
                    io.close()
                    sessions.trySend(session)
                }
            }
        }
    }

    private suspend fun serve(initial: IoStream, raw: IoStream, s: Session, upgraded: (IoStream) -> Unit) {
        var lines = Lines(initial)
        lines.write("220-fake.test ESMTP ready\r\n220 no spam please\r\n")
        while (true) {
            val line = lines.read() ?: return
            val verb = line.substringBefore(' ').uppercase()
            when {
                verb == "EHLO" -> {
                    s.ehloCount++
                    val out = StringBuilder("250-fake.test greets ${line.substringAfter(' ')}\r\n250-SIZE 10485760\r\n250-8BITMIME\r\n")
                    if (options.starttls && !s.secured) out.append("250-STARTTLS\r\n")
                    if (options.auth.isNotEmpty()) out.append("250-AUTH ${options.auth.joinToString(" ")}\r\n")
                    out.append("250 HELP\r\n")
                    lines.write(out.toString())
                }
                verb == "STARTTLS" && options.starttls && !s.secured -> {
                    lines.write("220 2.0.0 ready to start TLS\r\n")
                    val secured = tlsAccept(raw, tls)
                    upgraded(secured)
                    lines = Lines(secured)
                    s.secured = true
                }
                verb == "AUTH" -> {
                    val parts = line.split(' ')
                    val mechanism = parts.getOrNull(1)?.uppercase()
                    s.authMechanism = mechanism
                    s.authSecured = s.secured
                    val credentials = when (mechanism) {
                        "PLAIN" -> {
                            val token = parts.getOrNull(2) ?: run { lines.write("334 \r\n"); lines.read() ?: return }
                            val fields = Base64.decode(token).decodeToString().split('\u0000')
                            fields[1] to fields[2]
                        }
                        "LOGIN" -> {
                            lines.write("334 VXNlcm5hbWU6\r\n")
                            val user = Base64.decode(lines.read() ?: return).decodeToString()
                            lines.write("334 UGFzc3dvcmQ6\r\n")
                            user to Base64.decode(lines.read() ?: return).decodeToString()
                        }
                        else -> {
                            lines.write("504 5.5.4 unrecognized authentication type\r\n")
                            continue
                        }
                    }
                    if (credentials == options.user to options.password) {
                        s.credentials = credentials
                        lines.write("235 2.7.0 Authentication successful\r\n")
                    } else {
                        lines.write("535-5.7.8 Username and Password not accepted.\r\n535 5.7.8 Try again later\r\n")
                    }
                }
                verb == "MAIL" -> {
                    s.mailFrom = line.substringAfter('<').substringBefore('>')
                    lines.write("250 2.1.0 OK\r\n")
                }
                verb == "RCPT" -> {
                    val rcpt = line.substringAfter('<').substringBefore('>')
                    if (rcpt in options.refuse) {
                        lines.write("550 5.1.1 <$rcpt>: mailbox unavailable\r\n")
                    } else {
                        s.recipients += rcpt
                        lines.write("250 2.1.5 OK\r\n")
                    }
                }
                verb == "DATA" -> {
                    lines.write("354 end data with <CR><LF>.<CR><LF>\r\n")
                    val out = StringBuilder()
                    while (true) {
                        val l = lines.read() ?: return
                        if (l == ".") break
                        out.append(if (l.startsWith(".")) l.substring(1) else l).append("\r\n")
                    }
                    s.data = out.toString()
                    lines.write("250 2.0.0 queued\r\n")
                }
                verb == "QUIT" -> {
                    s.quit = true
                    lines.write("221 2.0.0 bye\r\n")
                    return
                }
                else -> lines.write("502 5.5.2 command not recognized\r\n")
            }
        }
    }

    /** CRLF line reader / writer on one stream. */
    private class Lines(private val stream: IoStream) {
        private val buf = Buffer(4096)
        private var pending = ByteArray(0)

        suspend fun read(): String? {
            while (true) {
                val lf = pending.indexOf('\n'.code.toByte())
                if (lf >= 0) {
                    check(lf > 0 && pending[lf - 1] == '\r'.code.toByte()) { "bare LF from client" }
                    val line = pending.decodeToString(0, lf - 1)
                    pending = pending.copyOfRange(lf + 1, pending.size)
                    return line
                }
                if (stream.read(buf) < 0) return null
                pending += buf.readAll()
            }
        }

        suspend fun write(text: String) {
            val bytes = text.encodeToByteArray()
            val out = Buffer(bytes.size)
            out.writeBytes(bytes)
            while (!out.isEmpty) stream.write(out)
            stream.flush()
        }
    }

    suspend fun stop() {
        stopRequested.complete(Unit)
        stopped.await()
    }

    companion object {
        suspend fun start(identity: TestIdentity, options: Options): FakeSmtpServer {
            ignoreSigpipe()
            val tls = TlsContext(server = true, certificateChainPem = identity.certificate, privateKeyPem = identity.key)
            val server = FakeSmtpServer(tls, options)
            server.worker.execute(TransferMode.SAFE, { server }) { it.runOnThisThread() }
            server.port = withTimeout(10_000) { server.bound.await() }
            return server
        }
    }
}
