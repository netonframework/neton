// neton-ai/src/commonMain/kotlin/neton/ai/config/UsageBuilder.kt
package neton.ai.config

import neton.ai.usage.AiUsageRecorder

class UsageBuilder internal constructor() {
    var recorder: AiUsageRecorder? = null
    internal fun build() = UsageConfig(recorder)
}
