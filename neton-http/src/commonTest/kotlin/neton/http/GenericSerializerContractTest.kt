package neton.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.core.http.JsonContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 泛型序列化契约测试（beta1 冻结）
 *
 * 冻结规则：@Serializable 返回类型（含泛型）必须通过 KSP 生成的显式 serializer 序列化，
 * 输出统一走 JsonContent，绕开 Ktor 的 runtime guessSerializer()。
 *
 * 防回潮：PageResponse<UserVO> 级别的泛型必须能正确序列化/反序列化，
 * 更深层嵌套（ApiResponse<PageResponse<UserVO>>）也不能崩。
 */
class GenericSerializerContractTest {

    @Serializable
    data class TestPageResponse<T>(
        val items: List<T>,
        val total: Long,
        val page: Int,
        val size: Int,
        val totalPages: Int
    )

    @Serializable
    data class TestUserVO(
        val id: Long,
        val username: String,
        val status: Int
    )

    @Serializable
    data class TestApiResponse<T>(
        val code: Int,
        val message: String,
        val data: T?
    )

    // --- Test 1: 单层泛型 PageResponse<UserVO> 序列化 ---
    @Test
    fun pageResponse_serializes_correctly() {
        val page = TestPageResponse(
            items = listOf(
                TestUserVO(1, "admin", 1),
                TestUserVO(2, "guest", 0)
            ),
            total = 2,
            page = 1,
            size = 20,
            totalPages = 1
        )

        // 模拟 KSP 生成的序列化器表达式
        val serializer = TestPageResponse.serializer(TestUserVO.serializer())
        val json = Json.encodeToString(serializer, page)
        val content = JsonContent(json)

        // 验证 JsonContent 包含有效 JSON
        assertTrue(content.json.contains("\"items\""))
        assertTrue(content.json.contains("\"admin\""))
        assertTrue(content.json.contains("\"total\":2"))

        // 反序列化验证
        val decoded = Json.decodeFromString(serializer, content.json)
        assertEquals(2, decoded.items.size)
        assertEquals("admin", decoded.items[0].username)
        assertEquals(2L, decoded.total)
        assertEquals(1, decoded.page)
    }

    // --- Test 2: 空列表序列化不崩 ---
    @Test
    fun pageResponse_emptyItems_serializes_correctly() {
        val page = TestPageResponse<TestUserVO>(
            items = emptyList(),
            total = 0,
            page = 0,
            size = 0,
            totalPages = 0
        )

        val serializer = TestPageResponse.serializer(TestUserVO.serializer())
        val json = Json.encodeToString(serializer, page)
        val content = JsonContent(json)

        assertTrue(content.json.contains("\"items\":[]"))
        assertEquals(0L, Json.decodeFromString(serializer, content.json).total)
    }

    // --- Test 3: 二层嵌套泛型 ApiResponse<PageResponse<UserVO>> 序列化 ---
    @Test
    fun nestedGeneric_apiResponse_pageResponse_serializes_correctly() {
        val innerPage = TestPageResponse(
            items = listOf(TestUserVO(1, "admin", 1)),
            total = 1, page = 1, size = 10, totalPages = 1
        )
        val response = TestApiResponse(
            code = 200,
            message = "ok",
            data = innerPage
        )

        // 二层嵌套 serializer 表达式
        val serializer = TestApiResponse.serializer(
            TestPageResponse.serializer(TestUserVO.serializer())
        )
        val json = Json.encodeToString(serializer, response)
        val content = JsonContent(json)

        assertTrue(content.json.contains("\"code\":200"))
        assertTrue(content.json.contains("\"admin\""))

        val decoded = Json.decodeFromString(serializer, content.json)
        assertEquals(200, decoded.code)
        assertEquals(1, decoded.data?.items?.size)
        assertEquals("admin", decoded.data?.items?.get(0)?.username)
    }

    // --- Test 4: 非泛型 @Serializable 也能正常序列化 ---
    @Test
    fun nonGeneric_serializable_serializes_correctly() {
        val user = TestUserVO(42, "test", 1)
        val json = Json.encodeToString(TestUserVO.serializer(), user)
        val content = JsonContent(json)

        val decoded = Json.decodeFromString(TestUserVO.serializer(), content.json)
        assertEquals(42L, decoded.id)
        assertEquals("test", decoded.username)
    }

    // --- Test 5: JsonContent 是纯值容器，不做二次转义 ---
    @Test
    fun jsonContent_is_raw_json_string() {
        val raw = """{"key":"value"}"""
        val content = JsonContent(raw)
        assertEquals(raw, content.json)
    }
}
