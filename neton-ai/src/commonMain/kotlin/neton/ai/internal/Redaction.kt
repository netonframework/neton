// neton-ai/src/commonMain/kotlin/neton/ai/internal/Redaction.kt
package neton.ai.internal

/**
 * Header keys whose VALUES MUST NEVER appear in logs (case-insensitive). Mirrors and includes
 * neton-http-client's DEFAULT_REDACTED_HEADERS for AI-specific keys.
 */
internal val REDACTED_HEADER_KEYS: Set<String> = setOf(
    "authorization", "x-api-key", "api-key", "anthropic-api-key",
    "cookie", "set-cookie", "proxy-authorization", "openai-organization",
)

internal fun Map<String, String>.withRedactedValues(): Map<String, String> =
    mapValues { (k, v) ->
        if (k.lowercase() in REDACTED_HEADER_KEYS) "<redacted>" else v
    }
