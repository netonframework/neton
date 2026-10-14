package neton.ai.protocol

import neton.ai.protocol.endpoint.Endpoint
import neton.ai.protocol.endpoint.EndpointUsage
import neton.ai.protocol.endpoint.EndpointUsageExtractor
import neton.ai.protocol.transport.MultipartForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointUsageTest {
    @Test
    fun perEndpointFacts() {
        val emb = EndpointUsageExtractor.extract(Endpoint.EMBEDDINGS, null, """{"data":[],"usage":{"prompt_tokens":7,"total_tokens":7}}""")
        assertEquals(7L, emb.tokens?.inputTokens)
        assertNull(emb.tokens?.outputTokens)

        val img = EndpointUsageExtractor.extract(Endpoint.IMAGES_GENERATIONS, null,
            """{"data":[{"b64_json":"x"},{"b64_json":"y"}],"usage":{"input_tokens":50,"output_tokens":4000,"input_tokens_details":{"image_tokens":40}}}""")
        assertEquals(2.0, img.units[EndpointUsage.UNIT_IMAGES])
        assertEquals(40L, img.tokens?.imageTokens)

        val tts = EndpointUsageExtractor.extract(Endpoint.AUDIO_SPEECH, """{"model":"tts","input":"你好😀","voice":"a"}""", null)
        assertEquals(3.0, tts.units[EndpointUsage.UNIT_CHARACTERS])
        assertNull(tts.tokens)

        val stt = EndpointUsageExtractor.extract(Endpoint.AUDIO_TRANSCRIPTIONS, null, """{"text":"hi","usage":{"type":"duration","seconds":12.5}}""")
        assertEquals(12.5, stt.units[EndpointUsage.UNIT_AUDIO_SECONDS])
        val sttTokens = EndpointUsageExtractor.extract(Endpoint.AUDIO_TRANSCRIPTIONS, null,
            """{"text":"hi","usage":{"type":"tokens","input_tokens":30,"output_tokens":5,"input_token_details":{"audio_tokens":28}}}""")
        assertEquals(28L, sttTokens.tokens?.audioInputTokens)

        val rerank = EndpointUsageExtractor.extract(Endpoint.RERANK, """{"query":"q","documents":["a","b","c"]}""", """{"results":[],"meta":{"billed_units":{"search_units":1}}}""")
        assertEquals(1.0, rerank.units[EndpointUsage.UNIT_SEARCH_UNITS])
        assertEquals(3.0, rerank.units[EndpointUsage.UNIT_DOCUMENTS])

        assertEquals(Endpoint.AUDIO_SPEECH, Endpoint.fromPath("/v1/audio/speech?x=1"))
    }

    @Test
    fun multipartModelRewriteKeepsFileBytes() {
        val b = "XyZ"
        val body = ("--$b\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\nRIFF\u0000data\r\n--$b--\r\n").encodeToByteArray()
        assertEquals(b, MultipartForm.boundary("multipart/form-data; boundary=$b"))
        val out = MultipartForm.replaceField(body, b, "model", "mapped-model")
        assertEquals("mapped-model", MultipartForm.field(out, b, "model"))
        assertEquals(body.size + "mapped-model".length - "whisper-1".length, out.size)
    }
}
