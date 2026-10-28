// neton-ai/src/commonMain/kotlin/neton/ai/config/UsageBuilder.kt
package neton.ai.config

import neton.ai.usage.AiUsageRecorder

@neton.ai.ExperimentalNetonAiApi
class UsageBuilder internal constructor() {
    var recorder: AiUsageRecorder? = null
    internal fun build() = UsageConfig(recorder)
}
