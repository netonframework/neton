package neton.ai.protocol

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.ai.protocol.convert.MediaResolver
import neton.ai.protocol.convert.ProtocolConverter
import neton.ai.protocol.convert.ReasoningSuffix
import neton.ai.protocol.convert.convertRequestResolvingMedia
import neton.ai.protocol.diag.DiagnosticCodes
import neton.ai.protocol.ir.MediaSource
import neton.ai.protocol.ir.ReasoningMode
import neton.ai.protocol.ir.WireFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostHooksTest {
    @Test
    fun reasoningSuffixes() {
        assertEquals(ReasoningMode.Enabled to 8000, ReasoningSuffix.parse("claude-sonnet-4-5-thinking-8000").reasoning!!.let { it.mode to it.budgetTokens })
        assertEquals("claude-sonnet-4-5", ReasoningSuffix.parse("claude-sonnet-4-5-thinking").model)
        assertEquals(ReasoningMode.Disabled, ReasoningSuffix.parse("qwen3-nothinking").reasoning!!.mode)
        assertEquals("high", ReasoningSuffix.parse("gpt-5-high").reasoning!!.effort)
        assertNull(ReasoningSuffix.parse("gpt-4o").reasoning)
    }

    @Test
    fun urlImagesAreInlinedOnlyThroughTheHostResolver() = runTest {
        val body = obj("""{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"see"},
            {"type":"image_url","image_url":{"url":"https://x/a.png"}},{"type":"image_url","image_url":{"url":"https://x/a.png"}}]}]}""")
        // 不解析：Converse 不收 URL，报 error 级诊断
        val plain = ProtocolConverter.convertRequest(body, WireFormat.OPENAI_CHAT, WireFormat.BEDROCK_CONVERSE)
        assertTrue(plain.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_MEDIA_TYPE })
        var calls = 0
        val resolved = ProtocolConverter.convertRequestResolvingMedia(body, WireFormat.OPENAI_CHAT, WireFormat.BEDROCK_CONVERSE,
            MediaResolver { calls++; MediaSource.Base64("QUJD", "image/png") })
        assertEquals(1, calls)  // 同一 URL 只解析一次
        val content = resolved.value["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("QUJD", content[1].jsonObject["image"]!!.jsonObject["source"]!!.jsonObject["bytes"]!!.jsonPrimitive.content)
        assertTrue(resolved.diagnostics.isEmpty(), resolved.diagnostics.toString())
        // Anthropic 收 URL：不调用解析器
        ProtocolConverter.convertRequestResolvingMedia(body, WireFormat.OPENAI_CHAT, WireFormat.ANTHROPIC, MediaResolver { error("must not fetch") })
    }
}
