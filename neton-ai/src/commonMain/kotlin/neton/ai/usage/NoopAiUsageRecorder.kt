package neton.ai.usage

@neton.ai.ExperimentalNetonAiApi
object NoopAiUsageRecorder : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {}
}
