package neton.http

import neton.http.mail.SmtpAccount
import kotlin.test.*

class SmtpCredentialDisplayTest {
    @Test fun credentialDisplayNeverContainsUsernameOrPassword() {
        val account = SmtpAccount("localhost", 465, "synthetic-user", "synthetic-secret", true, false)
        for (display in listOf(account.toString(), "account=$account", account.copy().toString())) {
            assertFalse(display.contains(account.username))
            assertFalse(display.contains(account.password))
            assertTrue(display.contains("<redacted>"))
        }
    }
}
