package neton.http.mail

/** 平台默认的 SMTP 实现：POSIX 走 libcurl（有 TLS），Windows 走纯明文的 Ktor socket 实现。 */
expect fun defaultSmtpTransport(): SmtpTransport
