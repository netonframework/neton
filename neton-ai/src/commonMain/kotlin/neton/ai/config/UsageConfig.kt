// neton-ai/src/commonMain/kotlin/neton/ai/config/UsageConfig.kt
package neton.ai.config

import neton.ai.usage.AiUsageRecorder

@neton.ai.ExperimentalNetonAiApi
data class UsageConfig(val recorder: AiUsageRecorder?)
