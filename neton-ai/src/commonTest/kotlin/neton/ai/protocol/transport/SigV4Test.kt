package neton.ai.protocol.transport

import neton.ai.protocol.transport.aws.SigV4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SigV4Test {
    @Test
    fun signsTheOfficialExampleShape() {
        val creds = SigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        val nowMs = 1440938160_000L  // 2015-08-30T12:36:00Z
        assertEquals("20150830T123600Z", SigV4.amzDate(nowMs))
        val signed = SigV4.sign(
            creds, "us-east-1", "iam", "GET", "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
            mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"), ByteArray(0), nowMs,
        )
        // 输入取自 AWS 文档的 SigV4 示例（IAM ListUsers）；期望签名由独立的 Python hmac/hashlib 实现
        // 按规范逐步算出（harness 的假 Bedrock 上游用同一套 Python 校验真实请求），钉住算法不漂移。
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=33f5dad2191de0cb4b7ab912f876876c2c4f72e2991a458f9499233c7b992438",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun canonicalFormsAndHelpers() {
        assertEquals("/model/anthropic.claude-3%253A0/invoke", SigV4.canonicalUri("/model/anthropic.claude-3%3A0/invoke"))
        assertEquals("Version=2010-05-08&action=ListUsers", SigV4.canonicalQuery("action=ListUsers&Version=2010-05-08"))
        assertEquals("us-east-1", SigV4.regionFromHost("bedrock-runtime.us-east-1.amazonaws.com"))
        assertEquals(null, SigV4.regionFromHost("example.com"))
        assertEquals(SigV4.Credentials("AK", "SK", "TOK"), SigV4.parseCredentials("AK:SK:TOK"))
        assertEquals("a%3Ab%2Fc", SigV4.uriEncode("a:b/c", encodeSlash = true))
    }

    @Test
    fun amzDateHandlesLeapYearsAndEpoch() {
        assertEquals("20240229T000000Z", SigV4.amzDate(1709164800_000L))
        assertEquals("19700101T000000Z", SigV4.amzDate(0))
    }
}
