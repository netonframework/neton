package neton.ai.provider

import neton.ai.AiUsage

data class ProviderEmbedResponse(
    val embeddings: List<FloatArray>,
    val usage: AiUsage?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderEmbedResponse) return false
        if (embeddings.size != other.embeddings.size) return false
        for (i in embeddings.indices) if (!embeddings[i].contentEquals(other.embeddings[i])) return false
        return usage == other.usage
    }
    override fun hashCode(): Int {
        var h = embeddings.fold(0) { a, arr -> 31 * a + arr.contentHashCode() }
        h = 31 * h + (usage?.hashCode() ?: 0)
        return h
    }
}
