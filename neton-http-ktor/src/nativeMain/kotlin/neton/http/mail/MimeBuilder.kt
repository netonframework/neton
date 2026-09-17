package neton.http.mail

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 组一封最简单的 MIME 邮件：UTF-8 主题（RFC 2047）、base64 正文、Date / Message-ID 齐全。 */
@OptIn(ExperimentalEncodingApi::class)
object MimeBuilder {
    fun build(m: SmtpMessage): String {
        val name = m.fromName
        val from = if (!name.isNullOrBlank()) "${encodeWord(name)} <${m.from}>" else m.from
        val body = Base64.encode(m.body.encodeToByteArray()).chunked(76).joinToString("\r\n")
        return buildString {
            append("From: ").append(from).append("\r\n")
            append("To: ").append(m.to.joinToString(", ")).append("\r\n")
            append("Subject: ").append(encodeWord(m.subject)).append("\r\n")
            append("Date: ").append(rfc2822Now()).append("\r\n")
            append("Message-ID: <").append(kotlin.random.Random.nextLong().toString(16)).append('.')
                .append(kotlin.time.Clock.System.now().toEpochMilliseconds()).append('@').append(m.from.substringAfter('@', "localhost")).append(">\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: ").append(if (m.html) "text/html" else "text/plain").append("; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(body)
        }
    }

    /** RFC 2047：非 ASCII 才编码，纯 ASCII 原样。 */
    fun encodeWord(s: String): String =
        if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?${Base64.encode(s.encodeToByteArray())}?="

    fun rfc2822Now(): String {
        val secs = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000
        var days = secs / 86400; var rem = (secs % 86400).toInt(); if (rem < 0) { rem += 86400; days -= 1 }
        val z = days + 719468; val era = (if (z >= 0) z else z - 146096) / 146097; val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1; val m = if (mp < 10) mp + 3 else mp - 9; if (m <= 2) y += 1
        val dow = ((days % 7 + 10) % 7).toInt()  // 1970-01-01 是周四
        val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val mons = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        fun p2(n: Long) = n.toString().padStart(2, '0')
        return "${dows[dow]}, ${p2(d)} ${mons[(m - 1).toInt()]} $y ${p2((rem / 3600).toLong())}:${p2(((rem % 3600) / 60).toLong())}:${p2((rem % 60).toLong())} +0000"
    }
}

class SmtpException(message: String) : RuntimeException(message)
