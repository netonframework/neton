package neton.ai

import neton.ai.internal.aggregateUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageAggregatorTest {

    @Test fun emptyListReturnsNull() = assertNull(aggregateUsage(emptyList()))

    @Test fun allNullEntriesReturnsNull() = assertNull(aggregateUsage(listOf(null, null, null)))

    @Test fun singleUsageReturnsSelf() {
        val u = AiUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30)
        assertEquals(u, aggregateUsage(listOf(u)))
    }

    @Test fun sumsNonNullFieldsAcrossRounds() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val u2 = AiUsage(inputTokens = 7, outputTokens = 3, totalTokens = 10)
        val u3 = AiUsage(inputTokens = 2, outputTokens = 1, totalTokens = 3)
        assertEquals(
            AiUsage(inputTokens = 19, outputTokens = 9, totalTokens = 28),
            aggregateUsage(listOf(u1, u2, u3)),
        )
    }

    @Test fun preservesNullPerFieldWhenAllNullForThatField() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = null, totalTokens = null)
        val u2 = AiUsage(inputTokens = 5, outputTokens = null, totalTokens = null)
        assertEquals(
            AiUsage(inputTokens = 15, outputTokens = null, totalTokens = null),
            aggregateUsage(listOf(u1, u2)),
        )
    }

    @Test fun mixedNullAndPresentSumsOnlyPresent() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = 5, totalTokens = null)
        val u2 = AiUsage(inputTokens = null, outputTokens = 3, totalTokens = 8)
        assertEquals(
            AiUsage(inputTokens = 10, outputTokens = 8, totalTokens = 8),
            aggregateUsage(listOf(u1, u2)),
        )
    }

    @Test fun skipsNullEntries() {
        val u = AiUsage(inputTokens = 4, outputTokens = 2, totalTokens = 6)
        assertEquals(u, aggregateUsage(listOf(null, u, null)))
    }
}
