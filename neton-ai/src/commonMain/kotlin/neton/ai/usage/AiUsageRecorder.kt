package neton.ai.usage

@neton.ai.ExperimentalNetonAiApi
interface AiUsageRecorder {
    suspend fun record(event: AiUsageEvent)
}
