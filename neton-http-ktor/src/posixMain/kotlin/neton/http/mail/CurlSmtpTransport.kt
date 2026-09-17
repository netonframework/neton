package neton.http.mail

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neton.curl.*
import platform.posix.size_t

/**
 * libcurl 实现的 SMTP：`smtps://host:465`（隐式 TLS）、`smtp://host:587` + CURLUSESSL_ALL（STARTTLS）、
 * 两者都关则明文。证书按系统信任链校验（macOS SecureTransport / Linux OpenSSL）。
 * curl_easy_perform 是阻塞调用，放到 Default 调度器的线程上跑，60 秒总超时。
 */
@OptIn(ExperimentalForeignApi::class)
class CurlSmtpTransport : SmtpTransport {

    private class Upload(val bytes: ByteArray) { var offset = 0 }

    override suspend fun send(account: SmtpAccount, message: SmtpMessage) = withContext(Dispatchers.Default) {
        val payload = MimeBuilder.build(message).encodeToByteArray()
        val upload = Upload(payload)
        val ref = StableRef.create(upload)
        var rcpt: CPointer<curl_slist>? = null
        val handle = curl_easy_init() ?: throw SmtpException("curl_easy_init failed")
        try {
            val scheme = if (account.ssl) "smtps" else "smtp"
            curl_easy_setopt(handle, CURLOPT_URL, "$scheme://${account.host}:${account.port}")
            if (!account.ssl && account.starttls) curl_easy_setopt(handle, CURLOPT_USE_SSL, 3L)  // CURLUSESSL_ALL：必须 STARTTLS，失败即报错
            if (account.username.isNotBlank()) {
                curl_easy_setopt(handle, CURLOPT_USERNAME, account.username)
                curl_easy_setopt(handle, CURLOPT_PASSWORD, account.password)
            }
            curl_easy_setopt(handle, CURLOPT_MAIL_FROM, "<${message.from}>")
            for (r in message.to) rcpt = curl_slist_append(rcpt, "<$r>")
            curl_easy_setopt(handle, CURLOPT_MAIL_RCPT, rcpt)
            curl_easy_setopt(handle, CURLOPT_READFUNCTION, READ_CALLBACK)
            curl_easy_setopt(handle, CURLOPT_READDATA, ref.asCPointer())
            curl_easy_setopt(handle, CURLOPT_UPLOAD, 1L)
            curl_easy_setopt(handle, CURLOPT_CONNECTTIMEOUT, 15L)
            curl_easy_setopt(handle, CURLOPT_TIMEOUT, 60L)
            curl_easy_setopt(handle, CURLOPT_NOSIGNAL, 1L)
            val code = curl_easy_perform(handle)
            if (code != CURLE_OK) {
                val msg = curl_easy_strerror(code)?.toKString() ?: "curl error $code"
                throw SmtpException("SMTP send failed ($scheme://${account.host}:${account.port}): $msg")
            }
        } finally {
            curl_slist_free_all(rcpt)
            curl_easy_cleanup(handle)
            ref.dispose()
        }
    }

    companion object {
        init { curl_global_init(CURL_GLOBAL_DEFAULT.toLong()) }

        /** libcurl 的读回调：把 MIME 字节按块喂给它；返回 0 表示传完。 */
        private val READ_CALLBACK = staticCFunction { buffer: CPointer<ByteVar>?, size: size_t, nitems: size_t, userdata: COpaquePointer? ->
            val up = userdata?.asStableRef<Upload>()?.get() ?: return@staticCFunction 0.convert<size_t>()
            val max = (size * nitems).toInt()
            val remaining = up.bytes.size - up.offset
            if (buffer == null || remaining <= 0 || max <= 0) return@staticCFunction 0.convert<size_t>()
            val n = minOf(max, remaining)
            up.bytes.usePinned { pinned -> platform.posix.memcpy(buffer, pinned.addressOf(up.offset), n.convert()) }
            up.offset += n
            n.convert<size_t>()
        }
    }
}
