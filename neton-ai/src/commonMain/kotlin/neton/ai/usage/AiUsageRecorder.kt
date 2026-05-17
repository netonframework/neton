package neton.ai.usage

interface AiUsageRecorder {
    suspend fun record(event: AiUsageEvent)
}
