package neton.http.mail

actual fun defaultSmtpTransport(): SmtpTransport = KtorSmtpTransport()
