package neton.ai.usage

class LoggingAiUsageRecorder(
    val emit: (line: String) -> Unit,
    val metadataAllowlist: Set<String> = DEFAULT_METADATA_ALLOWLIST,
) : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {
        val safe = event.requestMetadata.filterKeys { it in metadataAllowlist }
        val sb = StringBuilder("ai.usage")
        sb.append(" provider=").append(event.providerId)
        sb.append(" model=").append(event.modelName)
        sb.append(" round=").append(event.round)
        sb.append(" success=").append(event.success)
        event.errorKind?.let { sb.append(" errorKind=").append(it) }
        event.finishReason?.let { sb.append(" finish=").append(it.name) }
        event.usage?.inputTokens?.let { sb.append(" in=").append(it) }
        event.usage?.outputTokens?.let { sb.append(" out=").append(it) }
        event.usage?.totalTokens?.let { sb.append(" total=").append(it) }
        sb.append(" durMs=").append(event.durationMillis)
        event.requestId?.let { sb.append(" requestId=").append(it) }
        for ((k, v) in safe) sb.append(' ').append(k).append('=').append(v)
        emit(sb.toString())
    }

    companion object {
        val DEFAULT_METADATA_ALLOWLIST: Set<String> = setOf(
            "requestId", "traceId", "userId", "businessTag", "channelId",
        )
    }
}
