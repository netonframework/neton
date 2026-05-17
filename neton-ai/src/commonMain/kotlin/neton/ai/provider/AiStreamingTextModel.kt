package neton.ai.provider

import kotlinx.coroutines.flow.Flow
import neton.ai.AiStreamEvent

/**
 * Streaming text capability. Provider MUST:
 *  - NOT emit AiStreamEvent.Started (AiClient owns Started after first provider event)
 *  - NOT emit AiStreamEvent.ToolResultReady (AiClient owns local executor lifecycle)
 *  - emit exactly one AiStreamEvent.Completed OR Failed, unless cancelled
 */
interface AiStreamingTextModel : AiTextModel {
    fun stream(request: ProviderCallRequest): Flow<AiStreamEvent>
}
