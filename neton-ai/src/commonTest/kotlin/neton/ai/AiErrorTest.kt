package neton.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiErrorTest {

    @Test fun kindNetwork() = assertEquals("Network", AiError.Network("x", null).kind)
    @Test fun kindTimeout() = assertEquals("Timeout", AiError.Timeout("x", null).kind)
    @Test fun kindRateLimited() = assertEquals("RateLimited", AiError.RateLimited(null, "x").kind)
    @Test fun kindServerError() = assertEquals("ServerError", AiError.ServerError(503, "x").kind)
    @Test fun kindUnauthorized() = assertEquals("Unauthorized", AiError.Unauthorized("x").kind)
    @Test fun kindForbidden() = assertEquals("Forbidden", AiError.Forbidden("x").kind)
    @Test fun kindInvalidRequest() = assertEquals("InvalidRequest", AiError.InvalidRequest("x").kind)
    @Test fun kindContextLengthExceeded() = assertEquals("ContextLengthExceeded", AiError.ContextLengthExceeded("x").kind)
    @Test fun kindContentFilter() = assertEquals("ContentFilter", AiError.ContentFilter("x").kind)
    @Test fun kindModelNotFound() = assertEquals("ModelNotFound", AiError.ModelNotFound("openai:gpt-x", "x").kind)
    @Test fun kindUnknown() = assertEquals("Unknown", AiError.Unknown("x", null).kind)

    @Test fun fallbackEligibleNetwork() = assertTrue(AiError.Network("x", null).isFallbackEligible())
    @Test fun fallbackEligibleTimeout() = assertTrue(AiError.Timeout("x", null).isFallbackEligible())
    @Test fun fallbackEligibleRateLimited() = assertTrue(AiError.RateLimited(null, "x").isFallbackEligible())
    @Test fun fallbackEligibleServer5xx() = assertTrue(AiError.ServerError(503, "x").isFallbackEligible())
    @Test fun fallbackIneligibleServer4xx() = assertFalse(AiError.ServerError(404, "x").isFallbackEligible())
    @Test fun fallbackIneligibleUnauthorized() = assertFalse(AiError.Unauthorized("x").isFallbackEligible())
    @Test fun fallbackIneligibleForbidden() = assertFalse(AiError.Forbidden("x").isFallbackEligible())
    @Test fun fallbackIneligibleInvalidRequest() = assertFalse(AiError.InvalidRequest("x").isFallbackEligible())
    @Test fun fallbackIneligibleContextLengthExceeded() = assertFalse(AiError.ContextLengthExceeded("x").isFallbackEligible())
    @Test fun fallbackIneligibleContentFilter() = assertFalse(AiError.ContentFilter("x").isFallbackEligible())
    @Test fun fallbackIneligibleModelNotFound() = assertFalse(AiError.ModelNotFound("x", "x").isFallbackEligible())
    @Test fun fallbackIneligibleUnknown() = assertFalse(AiError.Unknown("x", null).isFallbackEligible())

    @Test fun aiExceptionWrapsErrorAndPropagatesMessageAndCause() {
        val cause = RuntimeException("boom")
        val err = AiError.Network("net failed", cause)
        val ex = AiException(err)
        assertEquals("net failed", ex.message)
        assertEquals(cause, ex.cause)
        assertEquals(err, ex.error)
    }
}
