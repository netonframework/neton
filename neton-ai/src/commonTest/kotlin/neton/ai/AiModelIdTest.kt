package neton.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelIdTest {

    @Test fun parsesSimpleProviderAndModel() {
        assertEquals(AiModelId("openai", "gpt-4o-mini"), AiModelId.parse("openai:gpt-4o-mini"))
    }

    @Test fun splitsAtFirstColonOnly() {
        val id = AiModelId.parse("openrouter:anthropic/claude-sonnet-4.5")
        assertEquals("openrouter", id.providerId)
        assertEquals("anthropic/claude-sonnet-4.5", id.modelName)
    }

    @Test fun modelNameMayContainSlashesAndDots() {
        val id = AiModelId.parse("vertex:gemini-1.5-pro/preview")
        assertEquals("vertex", id.providerId)
        assertEquals("gemini-1.5-pro/preview", id.modelName)
    }

    @Test fun modelNameMayContainAdditionalColons() {
        val id = AiModelId.parse("custom:family:v2:beta")
        assertEquals("custom", id.providerId)
        assertEquals("family:v2:beta", id.modelName)
    }

    @Test fun toStringRoundTrip() {
        val id = AiModelId("anthropic", "claude-sonnet-4.5")
        assertEquals("anthropic:claude-sonnet-4.5", id.toString())
        assertEquals(id, AiModelId.parse(id.toString()))
    }

    @Test fun rejectsMissingColon() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("just-a-model") }
    }

    @Test fun rejectsEmptyProvider() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse(":gpt-4o") }
    }

    @Test fun rejectsEmptyModel() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("openai:") }
    }

    @Test fun rejectsBlankString() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("") }
    }
}
