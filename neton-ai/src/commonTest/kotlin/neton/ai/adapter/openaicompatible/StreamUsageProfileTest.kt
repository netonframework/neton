package neton.ai.adapter.openaicompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.ToolChoice
import neton.ai.protocol.profile.ProviderProfiles
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.ProviderCallRequest
import neton.http.client.HttpClientMethod
import neton.http.testkit.ScriptedHttpClient
import neton.ai.testkit.bodyText
import neton.ai.testkit.sseChunks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 流式用量：是否发送 stream_options.include_usage 由厂商配置文件决定（spec/ai.md §7、§9）。 */
class StreamUsageProfileTest {
    private val request = ProviderCallRequest(
        messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
        tools = emptyList(), toolChoice = ToolChoice.Auto,
        temperature = null, maxTokens = null, topP = null, stopSequences = emptyList(), metadata = emptyMap(),
    )

    private suspend fun capturedStreamBody(streamUsage: Boolean): String {
        var body = ""
        val client = ScriptedHttpClient().onStream(HttpClientMethod.Post, "") { req ->
            body = req.bodyText() ?: ""
            sseChunks("data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }
        val provider = OpenAiCompatibleProvider(id = "p", httpClient = client, baseUrl = "https://example.com/v1", apiKey = "k", streamUsage = streamUsage)
        (provider.streamingTextModel("m") as AiStreamingTextModel).stream(request).toList()
        return body
    }

    @Test fun streamOptionsSentOnlyWhenProfileSaysSo() = runTest {
        assertTrue(capturedStreamBody(streamUsage = true).contains("\"stream_options\":{\"include_usage\":true}"))
        assertFalse(capturedStreamBody(streamUsage = false).contains("stream_options"))
    }

    @Test fun builtInProfiles_openAiIncludesUsage_unknownIsConservative() {
        assertEquals(true, ProviderProfiles.forBaseUrl("https://api.openai.com/v1").streamUsage)
        assertEquals(false, ProviderProfiles.forBaseUrl("https://my-vllm.internal:8000/v1").streamUsage)
    }
}
