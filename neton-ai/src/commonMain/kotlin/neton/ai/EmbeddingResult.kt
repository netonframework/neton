package neton.ai

data class EmbeddingResult(
    val embeddings: List<FloatArray>,     // same order as request.input
    val usage: AiUsage?,
    val providerId: String,
    val modelName: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingResult) return false
        if (embeddings.size != other.embeddings.size) return false
        for (i in embeddings.indices) if (!embeddings[i].contentEquals(other.embeddings[i])) return false
        return usage == other.usage && providerId == other.providerId && modelName == other.modelName
    }
    override fun hashCode(): Int {
        var result = embeddings.fold(0) { acc, arr -> 31 * acc + arr.contentHashCode() }
        result = 31 * result + (usage?.hashCode() ?: 0)
        result = 31 * result + providerId.hashCode()
        result = 31 * result + modelName.hashCode()
        return result
    }
}
