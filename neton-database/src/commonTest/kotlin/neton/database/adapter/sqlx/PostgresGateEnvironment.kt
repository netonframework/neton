package neton.database.adapter.sqlx

import neton.core.config.getEnv

/** CI must opt in explicitly: absent credentials must never count as a database pass. */
internal fun postgresTestUri(): String? = postgresTestUri(
    getEnv("NETON_DB_TEST_URI"),
    getEnv("NETON_DB_TEST_REQUIRED") == "true",
)

internal fun postgresTestUri(uri: String?, required: Boolean): String? {
    val value = uri?.takeIf { it.isNotBlank() }
    check(!required || value != null) { "NETON_DB_TEST_REQUIRED=true requires NETON_DB_TEST_URI" }
    if (required && value != null) {
        // The mandatory gate is only allowed to touch a disposable, local database.
        // Do not put the URI in errors: it may contain credentials.
        require(Regex("postgresql://[^:@/]+:[^@/]+@(127\\.0\\.0\\.1|localhost):[0-9]+/neton_gate_[a-z0-9_]+$").matches(value)) {
            "The PostgreSQL gate requires a loopback URI and a neton_gate_* database"
        }
    }
    return value
}
