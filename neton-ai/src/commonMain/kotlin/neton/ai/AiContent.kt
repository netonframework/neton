package neton.ai

/**
 * Message content variant. v0.1 ships only Text.
 * Multimodal variants (ImageUrl / ImageData / Audio) will be added in v0.2 as a
 * non-breaking sealed-interface extension; provider mappers will get a compile-time
 * non-exhaustive `when` warning when they need to be updated.
 */
sealed interface AiContent {
    data class Text(val text: String) : AiContent
}
