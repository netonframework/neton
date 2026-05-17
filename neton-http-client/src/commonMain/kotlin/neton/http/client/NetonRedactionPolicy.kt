package neton.http.client

/**
 * Redaction policy applied to all log output (any level).
 * Headers in [redactedHeaders] are NEVER printed, replaced with "<redacted>".
 * Body content is only printed when [allowBodyLogging] = true.
 *
 * Hard rule: API keys NEVER appear in logs regardless of policy.
 */
data class NetonRedactionPolicy(
    val redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS,
    val allowBodyLogging: Boolean = false,
)

/** Case-insensitive comparison expected when applying. */
val DEFAULT_REDACTED_HEADERS: Set<String> = setOf(
    "Authorization",
    "X-Api-Key",
    "api-key",
    "anthropic-api-key",
    "Cookie",
    "Set-Cookie",
    "Proxy-Authorization",
)
