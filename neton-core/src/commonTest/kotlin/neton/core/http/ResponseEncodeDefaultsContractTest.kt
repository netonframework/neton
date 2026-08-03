package neton.core.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 契约：**响应字段集由类型决定，不由运行时取值决定。**
 *
 * kotlinx 的裸 `Json` 默认 `encodeDefaults = false`，会把任何「当前值恰好等于声明默认值」
 * 的字段整个从响应 JSON 里删掉。客户端拿到的是「字段不存在」，与「字段值是 0」无从区分。
 *
 * 2026-07-26 生产实测：提现单 `fee = 0`、`status = 0(待审核)` 被丢掉，H5 渲染出
 * `¥NaN.NaN` 和状态「未知」；同一个根因还让后台的审批按钮永远不显示。
 *
 * 特别阴险的是 **Kotlin 客户端感知不到**——反序列化时缺失字段会被补回声明的默认值，
 * 于是 Kotlin↔Kotlin 的联调全绿，只有 JS/TS 端炸。所以这条必须由测试守着，
 * 不能指望联调发现。
 */
class ResponseEncodeDefaultsContractTest {

    @Serializable
    private data class WithdrawalVO(
        val id: Long = 0,
        /** 手续费为 0 是最常见的正常值，不是「没有值」。 */
        val fee: Long = 0,
        /** 0 = 待审核，恰好是默认值，也恰好是后台最需要看到的那个状态。 */
        val status: Int = 0,
        val remark: String = "",
    )

    private class RecordingResponse : HttpResponse {
        override var status: HttpStatus = HttpStatus.OK
        override val headers: MutableHeaders = TestHeaders()
        override val isCommitted: Boolean get() = body != null
        var body: String? = null
        override fun cookie(cookie: Cookie) {}
        override suspend fun write(data: ByteArray) { body = data.decodeToString() }
    }

    /**
     * 业务 VO 走的是 KSP 生成的路由：预序列化成字符串再交给 `json()`。
     * 这条断言的是那份预序列化必须带 `encodeDefaults = true`——裸 `Json` 会把
     * 整个对象编成 `{}`。
     */
    @Test
    fun fieldsEqualToTheirDefaultsSurviveSerialization() {
        val responseJson = Json { encodeDefaults = true }
        val body = responseJson.encodeToString(WithdrawalVO.serializer(), WithdrawalVO())

        assertContains(body, "\"fee\"", message = "fee dropped -> client renders NaN: $body")
        assertContains(body, "\"status\"", message = "status dropped -> client shows 'unknown': $body")

        // 反过来确认这条测试确实在测东西：裸 Json 会把它整个编空。
        assertEquals("{}", Json.encodeToString(WithdrawalVO.serializer(), WithdrawalVO()))
    }

    /**
     * 限流拦截器传的就是这个形状。
     *
     * 修复前这里会抛 `SerializationException`——`json()` 的形参是 `Any`，reified 的
     * `serializer()` 只能解析出 `Any` 的序列化器。也就是说**触发限流时客户端拿到的
     * 不是 429 JSON，而是一个序列化异常**。
     */
    @Test
    fun mapPayloadsAreEncodedInsteadOfThrowing() = runBlocking {
        val response = RecordingResponse()
        response.json(mapOf("code" to 429, "message" to "too many requests"))

        val body = response.body ?: error("nothing was written")
        assertContains(body, "\"code\":429")
        assertContains(body, "\"message\":\"too many requests\"")
    }

    @Test
    fun nestedListsAndNullsSurvive() = runBlocking {
        val response = RecordingResponse()
        response.json(mapOf("items" to listOf(1, 2), "next" to null))
        assertEquals("""{"items":[1,2],"next":null}""", response.body)
    }

    /** 传字符串时按原样写出，不重新序列化（KSP 预序列化就走这条）。 */
    @Test
    fun preEncodedStringsPassThroughUnchanged() = runBlocking {
        val response = RecordingResponse()
        response.json("""{"already":"encoded"}""")
        assertEquals("""{"already":"encoded"}""", response.body)
    }

    /**
     * 不认识的类型必须显式报错，说清该怎么办。
     *
     * 留一个看不懂的 `SerializationException` 才是最坏的：调用方会以为是数据问题，
     * 而真正的原因是这个入口本来就不接受任意对象。
     */
    @Test
    fun unsupportedTypesFailLoudlyWithAnActionableMessage() = runBlocking {
        val response = RecordingResponse()
        val error = assertFailsWith<IllegalArgumentException> {
            response.json(WithdrawalVO())
        }
        assertContains(error.message ?: "", "encodeDefaults")
    }
}
