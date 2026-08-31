package neton.core.http.adapter

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * spec http-engine-capabilities §2.4 / §8 的验收:
 * 能力不匹配必须**启动即失败**,且错误信息要说清"谁要求的"。
 *
 * 每个负向断言都检查**失败原因**,不只检查抛异常——只断言"抛了"会让无关错误
 * 冒充通过,这条门禁就白设了。
 */
class HttpCapabilityValidationTest {

    @Test
    fun passes_when_engine_covers_every_requirement() {
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.STREAMING_RESPONSE, "neton-ai (SSE relay)")
        // 不抛即通过。
        validateHttpCapabilities(
            "Ktor CIO",
            setOf(HttpCapability.STREAMING_RESPONSE, HttpCapability.MULTIPART),
            req,
        )
    }

    @Test
    fun passes_when_nothing_is_required() {
        validateHttpCapabilities("hyper4k", emptySet(), HttpCapabilityRequirements())
    }

    @Test
    fun fails_and_names_the_missing_capability() {
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.HTTP_2, "grpc-gateway")
        val e = assertFailsWith<HttpCapabilityException> {
            validateHttpCapabilities("Ktor CIO", setOf(HttpCapability.STREAMING_RESPONSE), req)
        }
        assertContains(e.message!!, "HTTP_2")
        assertContains(e.message!!, "Ktor CIO")
    }

    @Test
    fun failure_message_names_who_required_it() {
        // 只说"缺 STREAMING_RESPONSE"的报错,使用者无从下手:
        // 不知道该换引擎还是该去掉某个组件。
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.STREAMING_RESPONSE, "neton-ai (SSE relay)")
        val e = assertFailsWith<HttpCapabilityException> {
            validateHttpCapabilities("hyper4k", emptySet(), req)
        }
        assertContains(e.message!!, "neton-ai (SSE relay)")
    }

    @Test
    fun failure_message_lists_every_requester_of_one_capability() {
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.STREAMING_RESPONSE, "neton-ai")
        req.require(HttpCapability.STREAMING_RESPONSE, "module-cs")
        val e = assertFailsWith<HttpCapabilityException> {
            validateHttpCapabilities("hyper4k", emptySet(), req)
        }
        assertContains(e.message!!, "neton-ai")
        assertContains(e.message!!, "module-cs")
    }

    @Test
    fun reports_all_missing_capabilities_not_just_the_first() {
        // 一次说清全部,否则使用者要反复启动、逐个发现。
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.HTTP_2, "a")
        req.require(HttpCapability.MULTIPART, "b")
        req.require(HttpCapability.STREAMING_RESPONSE, "c")
        val e = assertFailsWith<HttpCapabilityException> {
            validateHttpCapabilities("hyper4k", emptySet(), req)
        }
        for (name in listOf("HTTP_2", "MULTIPART", "STREAMING_RESPONSE")) {
            assertContains(e.message!!, name)
        }
    }

    @Test
    fun failure_message_suggests_a_way_out() {
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.HTTP_2, "x")
        val e = assertFailsWith<HttpCapabilityException> {
            validateHttpCapabilities("Ktor CIO", emptySet(), req)
        }
        assertContains(e.message!!, "Hyper4kHttpAdapter")
    }

    @Test
    fun requirements_deduplicate_same_requester() {
        val req = HttpCapabilityRequirements()
        req.require(HttpCapability.MULTIPART, "same")
        req.require(HttpCapability.MULTIPART, "same")
        assertEquals(setOf("same"), req.requesters(HttpCapability.MULTIPART))
        assertEquals(setOf(HttpCapability.MULTIPART), req.all())
    }

    @Test
    fun empty_requirements_report_empty() {
        assertTrue(HttpCapabilityRequirements().isEmpty())
    }
}
