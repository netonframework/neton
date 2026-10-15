package neton.ai.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.isFallbackEligible

/**
 * 模型调用的重试：只重试可重试的错误（网络、超时、限流、过载与 5xx），指数退避（2s、4s…）；
 * 上游给了 Retry-After 且小于 60 秒或小于退避时间时，按它等待。取消立即传播。
 */
internal suspend fun <T> withRetries(
    maxRetries: Int,
    initialDelayMs: Long = 2_000,
    onRetry: suspend (attempt: Int, error: AiError, delayMs: Long) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 0
    var delayMs = initialDelayMs
    while (true) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            if (attempt >= maxRetries || !e.error.isFallbackEligible()) throw e
            val retryAfter = (e.error as? AiError.RateLimited)?.retryAfterMillis
            val wait = if (retryAfter != null && retryAfter >= 0 && (retryAfter < 60_000 || retryAfter < delayMs)) retryAfter else delayMs
            onRetry(attempt + 1, e.error, wait)
            delay(wait)
            delayMs *= 2
            attempt++
        }
    }
}
