package neton.ai.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiLogSink
import neton.ai.AiModelId
import neton.ai.telemetry.AiOperation
import neton.ai.telemetry.AiTelemetry
import neton.ai.telemetry.AiTelemetryContext
import neton.ai.telemetry.AiTelemetryEvent
import neton.ai.telemetry.AiTelemetrySettings
import kotlin.random.Random
import kotlin.time.TimeSource

/** 把事件分发给配置的遥测集成；没有集成时不构造事件。集成里的异常只记日志。 */
internal class TelemetryHub(
    private val integrations: List<AiTelemetry>,
    val settings: AiTelemetrySettings,
    private val logSink: AiLogSink?,
) {
    val enabled: Boolean get() = integrations.isNotEmpty()

    fun context(operation: AiOperation, functionId: String?, metadata: Map<String, String>) =
        AiTelemetryContext(Random.nextLong().toULong().toString(16).padStart(16, '0'), operation, functionId, metadata)

    suspend fun emit(build: () -> AiTelemetryEvent) {
        if (!enabled) return
        val event = build()
        for (t in integrations) observe(logSink, "telemetry ${event::class.simpleName}") { t.onEvent(event) }
    }

    /**
     * 包住一次操作，发出开始与结束（或失败）事件。取消也发失败事件（不可取消地发送），
     * 保证集成里为这次操作打开的状态（如 span）都能关闭。
     */
    suspend fun <T> operation(
        ctx: AiTelemetryContext,
        model: AiModelId?,
        start: () -> AiTelemetryEvent = { AiTelemetryEvent.OperationStart(ctx, model) },
        end: (T, Long) -> AiTelemetryEvent,
        block: suspend () -> T,
    ): T {
        if (!enabled) return block()
        val mark = TimeSource.Monotonic.markNow()
        emit(start)
        try {
            val r = block()
            emit { end(r, mark.elapsedNow().inWholeMilliseconds) }
            return r
        } catch (e: Throwable) {
            val error = when (e) {
                is AiException -> e.error
                is CancellationException -> AiError.Unknown("cancelled", e)
                else -> AiError.Unknown(e.message ?: "operation failed", e)
            }
            withContext(NonCancellable) { emit { AiTelemetryEvent.OperationError(ctx, mark.elapsedNow().inWholeMilliseconds, error) } }
            throw e
        }
    }

    companion object {
        val NONE = TelemetryHub(emptyList(), AiTelemetrySettings(), null)
    }
}
