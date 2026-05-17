package neton.ai.usage

object NoopAiUsageRecorder : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {}
}
