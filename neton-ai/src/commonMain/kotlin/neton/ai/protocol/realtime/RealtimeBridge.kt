package neton.ai.protocol.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import neton.ai.protocol.ir.WireFormat
import neton.ai.protocol.usage.RawUsage
import neton.ai.protocol.usage.Usage

/**
 * 一端 WebSocket 文本消息通道的抽象（spec/ai.md §3.4）。框架不持有 socket：
 * 宿主用 neton-websocket（或任何实现）把客户端连接与上游连接各包成一个通道。
 */
@neton.ai.ExperimentalNetonAiApi
interface RealtimeChannel {
    /** 下一条文本消息；对端关闭时返回 null。 */
    suspend fun receive(): String?
    suspend fun send(text: String)
    suspend fun close(code: Int = 1000, reason: String = "")
}

/** 宿主对客户端事件的处理结果。 */
@neton.ai.ExperimentalNetonAiApi
sealed interface ClientEventDecision {
    /** 原样或改写后转发给上游。 */
    data class Forward(val event: JsonObject) : ClientEventDecision

    /** 不转发，回给客户端一个协议原生的 error 事件（如禁用的参数、超出权限的模型）。 */
    data class Reject(val code: String, val message: String) : ClientEventDecision
}

/**
 * 实时会话的双向桥接：OpenAI Realtime API 与 Responses WebSocket 模式共用。
 *
 * - 客户端 → 上游：每个事件先交给 [onClientEvent]（改写模型、去掉禁用字段、按权限拒绝）；
 * - 上游 → 客户端：原样转发，同时从 `response.done`（Realtime）/ `response.completed`（Responses）
 *   提取用量交给 [onUsage]——计费只依据上游给出的事实；
 * - 任一端关闭或出错，关闭另一端；取消照常传播。
 */
@neton.ai.ExperimentalNetonAiApi
class RealtimeBridge(
    private val client: RealtimeChannel,
    private val upstream: RealtimeChannel,
    private val onClientEvent: suspend (JsonObject) -> ClientEventDecision = { ClientEventDecision.Forward(it) },
    private val onUsage: suspend (Usage) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run() = coroutineScope {
        val up = launch { pump(upstream, client, ::observeUpstream) }
        val down = launch { pumpClient() }
        // 任一方向结束即收尾
        select<Unit> {
            up.onJoin {}
            down.onJoin {}
        }
        up.cancel(); down.cancel()
        runCatching { client.close() }
        runCatching { upstream.close() }
    }

    private suspend fun pumpClient() {
        while (true) {
            val text = client.receive() ?: return
            val event = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (event == null) {
                client.send(errorEvent("invalid_event", "event must be a JSON object"))
                continue
            }
            when (val d = onClientEvent(event)) {
                is ClientEventDecision.Forward -> upstream.send(d.event.toString())
                is ClientEventDecision.Reject -> client.send(errorEvent(d.code, d.message, event["event_id"]))
            }
        }
    }

    private suspend fun pump(from: RealtimeChannel, to: RealtimeChannel, observe: suspend (String) -> Unit) {
        while (true) {
            val text = from.receive() ?: return
            try {
                observe(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 观测失败不影响转发
            }
            to.send(text)
        }
    }

    private suspend fun observeUpstream(text: String) {
        val event = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        RealtimeUsage.fromEvent(event)?.let { onUsage(it) }
    }

    private fun errorEvent(code: String, message: String, eventId: kotlinx.serialization.json.JsonElement? = null): String = buildJsonObject {
        put("type", "error")
        put("error", buildJsonObject {
            put("type", "invalid_request_error")
            put("code", code)
            put("message", message)
            eventId?.let { put("event_id", it) }
        })
    }.toString()
}

/** 实时协议的用量提取。 */
@neton.ai.ExperimentalNetonAiApi
object RealtimeUsage {
    /** `response.done`（Realtime）或 `response.completed`（Responses WebSocket）里的用量；其他事件返回 null。 */
    fun fromEvent(event: JsonObject): Usage? {
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "response.done" && type != "response.completed") return null
        val u = (event["response"] as? JsonObject)?.get("usage") as? JsonObject ?: return null
        fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        val inDetails = u["input_token_details"] as? JsonObject ?: u["input_tokens_details"] as? JsonObject
        val outDetails = u["output_token_details"] as? JsonObject ?: u["output_tokens_details"] as? JsonObject
        return Usage(
            inputTokens = u.n("input_tokens"),
            outputTokens = u.n("output_tokens"),
            cacheReadTokens = inDetails?.n("cached_tokens"),
            reasoningTokens = outDetails?.n("reasoning_tokens"),
            audioInputTokens = inDetails?.n("audio_tokens"),
            audioOutputTokens = outDetails?.n("audio_tokens"),
            imageTokens = inDetails?.n("image_tokens"),
            raw = RawUsage(if (type == "response.done") WireFormat.OPENAI_CHAT else WireFormat.OPENAI_RESPONSES, u),
        )
    }

    /** 把会话里的模型强制换成 [model]（Realtime `session.update` / Responses `response.create`）。 */
    fun withModel(event: JsonObject, model: String): JsonObject {
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "session.update" -> {
                val session = event["session"] as? JsonObject ?: return event
                if ("model" !in session) event else JsonObject(event + ("session" to JsonObject(session + ("model" to JsonPrimitive(model)))))
            }
            "response.create" -> if ("model" in event) JsonObject(event + ("model" to JsonPrimitive(model)))
                else (event["response"] as? JsonObject)?.takeIf { "model" in it }?.let { JsonObject(event + ("response" to JsonObject(it + ("model" to JsonPrimitive(model))))) } ?: event
            else -> event
        }
    }
}
