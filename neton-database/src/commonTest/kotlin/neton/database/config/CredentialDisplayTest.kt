package neton.database.config

import kotlin.test.*

class CredentialDisplayTest {
    @Test
    fun uriAndParsedCredentialsAreRedactedIncludingCopies() {
        val secret = "display-test-secret"
        val pg = PostgresUriInfo("localhost", 5432, secret, secret, "db", mapOf("token" to secret))
        val mysql = MysqlUriInfo("localhost", 3306, secret, secret, "db", mapOf("token" to secret))
        val config = DatabaseConfig(DatabaseDriver.POSTGRESQL, "postgresql://user:$secret@localhost/db")
        for (value in listOf(pg, pg.copy(), mysql, mysql.copy(), config, config.copy())) {
            assertFalse(value.toString().contains(secret))
        }
    }
}
