package neton.logging.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import neton.logging.CurrentLogContext
import neton.logging.LogContext
import neton.logging.LogLevel
import neton.logging.emptyFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JsonLogger 输出契约测试（v1 冻结）。
 */
class JsonLoggerContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun captureLogger(): Pair<JsonLogger, MutableList<String>> {
        val lines = mutableListOf<String>()
        val logger = JsonLogger(
            contextProvider = { CurrentLogContext.get() },
            minLevel = LogLevel.TRACE,
            output = { lines.add(it) }
        )
        return logger to lines
    }

    @Test
    fun requiredFields_ts_level_msg() {
        val (logger, lines) = captureLogger()
        logger.info("test-msg", emptyFields())
        assertEquals(1, lines.size)
        val obj = json.parseToJsonElement(lines.single()).jsonObject
        assertNotNull(obj["ts"], "ts must exist")
        assertNotNull(obj["level"], "level must exist")
        assertNotNull(obj["msg"], "msg must exist")
        assertEquals("test-msg", obj["msg"]?.jsonPrimitive?.content)
        assertEquals("INFO", obj["level"]?.jsonPrimitive?.content)
    }

    @Test
    fun contextInjection_traceId() {
        CurrentLogContext.set(LogContext(traceId = "trace-123", requestId = "req-456"))
        try {
            val (logger, lines) = captureLogger()
            logger.info("with-context", emptyFields())
            assertEquals(1, lines.size)
            val obj = json.parseToJsonElement(lines.single()).jsonObject
            assertEquals("trace-123", obj["traceId"]?.jsonPrimitive?.content)
            assertEquals("req-456", obj["requestId"]?.jsonPrimitive?.content)
        } finally {
            CurrentLogContext.clear()
        }
    }

    @Test
    fun sensitiveRedaction_authorization_token_password() {
        val (logger, lines) = captureLogger()
        logger.info(
            "redact-test",
            mapOf(
                "Authorization" to "Bearer secret",
                "token" to "jwt-xxx",
                "password" to "pwd",
                "other" to "keep"
            )
        )
        assertEquals(1, lines.size)
        val obj = json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("[REDACTED]", obj["Authorization"]?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", obj["token"]?.jsonPrimitive?.content)
        assertEquals("[REDACTED]", obj["password"]?.jsonPrimitive?.content)
        assertEquals("keep", obj["other"]?.jsonPrimitive?.content)
    }

    @Test
    fun errorRule_containsErrorAndStackTrace() {
        val (logger, lines) = captureLogger()
        val ex = RuntimeException("contract-test-error")
        logger.error("fail", emptyFields(), cause = ex)
        assertEquals(1, lines.size)
        val obj = json.parseToJsonElement(lines.single()).jsonObject
        val errorVal = obj["error"]?.jsonPrimitive?.content
        assertNotNull(errorVal)
        assertTrue(errorVal.contains("contract-test-error"), "error field must contain exception message")
        val stackVal = obj["stackTrace"]?.jsonPrimitive?.content
        assertNotNull(stackVal)
        assertTrue(stackVal.contains("RuntimeException") || stackVal.contains("contract-test-error"), "stackTrace must contain throwable info")
    }
}
