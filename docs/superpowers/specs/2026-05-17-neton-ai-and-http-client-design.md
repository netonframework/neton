# neton-ai + neton-http-client v0.1 Design Spec

- **Date**: 2026-05-17
- **Status**: Approved (pending user spec review) — ready for implementation plan
- **Authors**: zoujiaqing + Claude (interactive brainstorming session)
- **Target repos**:
  - `neton` (framework monorepo) — new modules `neton-http-client`, `neton-ai`
- **Reference**: `vercel-ai` (cloned at `/Users/zoujiaqing/projects/Neton/vercel-ai`) — used for wire-protocol cross-checking and conceptual mapping; **not** copied into Neton

---

## 0. Context & Goals

### 0.1 What this spec covers

This spec defines two new optional Neton framework infrastructure modules:

1. **`neton-http-client`** — KMP HTTP client infrastructure (engine selection, timeout, typed error, redaction, streaming body, SSE parser, retry primitive, cancellation propagation)
2. **`neton-ai`** — Provider-neutral AI core: chat (`generateText`), streaming chat (`streamText`), embeddings (`embed`), tool calling (local executor loop), model routing with fallback, token-usage recording

Together they enable Neton applications to call AI providers (initially OpenAI-compatible + Anthropic Claude) with a unified, type-safe, Kotlin-idiomatic API, while reusing a single shared HTTP-client foundation.

### 0.2 Why now

- Existing Neton modules (`neton-storage`, `privchat-client`) each repeated KMP Ktor Client engine selection / timeout / serialization configuration. A third repeat in `neton-ai` would entrench a pattern of every module re-implementing HTTP-client basics. Extracting `neton-http-client` is overdue.
- `neton-ai` is required by upcoming Assistant / Bot / 客服 product modules; building it provider-neutral upfront avoids vendor lock-in.

### 0.3 Inspiration vs. independence

- The public API surface is conceptually aligned with Vercel AI SDK Core (`generateText` / `streamText` / `streamObject` / `embed`), validated by 25+ providers in their ecosystem.
- This is **not** a port. No TypeScript-to-Kotlin translation. Types, errors, DSL, and component lifecycle follow Neton conventions (`NetonComponent` / `NetonContext` / TOML config / `Neton.LaunchBuilder` DSL).

### 0.4 Non-goals (explicitly out of scope)

`neton-ai` v0.1 does **not** include:

- Agent loop / autonomous task execution
- MCP (Model Context Protocol) client/server
- Sandbox / shell / filesystem tools
- Workflow / graph orchestration
- Memory compression / conversation summarization
- RAG pipeline (vector store SPI, retrievers, chunking)
- PrivChat-specific integration (`system_user`, channel binding)
- Assistant product layer (profile, session, runtime client)
- Auto JSON Schema generation from `@Serializable` types (typed tool DSL only does codec)
- Metrics SPI (observability flows through `AiUsageRecorder` only)
- Multimodal content (images / audio) — `AiContent` is `sealed` to allow v0.2 extension without breaking change
- Gemini / Google Vertex AI provider (deferred to v0.2)

`neton-http-client` v0.1 does **not** include:

- OAuth / cookie / session management
- WebSocket
- Multipart high-level API (raw Ktor multipart available via low-level escape hatch only)
- Circuit breaker / service discovery / load balancing
- GraphQL / gRPC
- Business envelope (`PrivchatServiceClient`-style RPC wrappers)
- Migration of `neton-storage` or `privchat-client` to use this new module (left as follow-up; out of scope to limit blast radius)

---

## 1. Module Boundaries

### 1.1 Three-module layout (existing + new)

| Module | Existing? | Role |
|---|---|---|
| `neton-http` | ✅ existing | HTTP **server** framework (Ktor server, routing, CORS, sessions) — unchanged |
| `neton-http-client` | 🆕 new | HTTP **client** infrastructure (KMP Ktor client, SSE, retry, redaction) |
| `neton-ai` | 🆕 new | AI provider abstraction, chat/stream/embed/tool/router/usage |

**Decision**: `neton-http` is **not** renamed in this round. A future major version may split into `neton-http-server` + `neton-http-client` + `neton-http-common`; for v0.1, the additive `neton-http-client` minimizes breaking change.

### 1.2 Dependency graph

```
neton-core
   ↑                      neton-logging
   |                          ↑
   +---- neton-http-client ---+   (Ktor Client per-platform engines)
              ↑
              |
          neton-ai (depends on neton-http-client + neton-core + neton-logging)
              ↑
              |
   (future) neton-application-module-assistant
   (future) privchat-application-module-assistant
   (future) assistant-runtime-service (Rust)
```

`neton-ai` **must not** depend directly on `io.ktor.client.*`; all HTTP goes through `neton-http-client`.

`neton-ai` public API **must not** expose:
- `io.ktor.*` types
- `kotlinx.serialization.json.JsonElement` (kotlinx.serialization may appear inside DSL builder internals for typed tool codec, but never on `AiClient` / `AiProvider` / event/result types)
- `kotlin.Result<T>` (use `AiException` + sealed `AiError` instead)

### 1.3 Package naming

- `neton-http-client` → package `neton.http.client`
- `neton-ai` → package `neton.ai`

(Follows existing Neton convention `neton.<module>`, not `com.netonframework.*`.)

---

## 2. `neton-http-client` v0.1

### 2.1 Scope

Thin KMP HTTP client infrastructure. **Does not know about AI, storage, or any business semantics.** Provides only:

1. `NetonHttpClient` interface
2. Per-platform Ktor engine selection (macOS Darwin / Linux CIO / Windows WinHTTP) via `expect/actual`
3. JSON request/response (kotlinx.serialization)
4. Typed timeout config
5. Typed error (`NetonHttpError`) — not raw exceptions on public API
6. Retry policy primitive (`NetonRetryPolicy` interface — no built-in implementation beyond noop in v0.1)
7. Redaction policy (`NetonRedactionPolicy`) — header allowlist + body allowlist
8. Streaming byte/text body primitives
9. SSE parser primitive
10. Cancellation propagation (Flow cancel → HTTP body close)

### 2.2 KMP targets

Follow existing Neton modules:

```kotlin
kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
}
```

Per-platform Ktor engine dependencies:

```kotlin
commonMain: ktor.client.core, ktor.client.content.negotiation,
            ktor.client.logging, ktor.serialization.kotlinx.json
macosMain:  ktor.client.darwin
linuxMain:  ktor.client.cio
mingwX64:   ktor.client.winhttp
```

(Pattern proven by `neton-storage` and `privchat-client`.)

### 2.3 Public API sketch

```kotlin
package neton.http.client

interface NetonHttpClient {
    suspend fun request(request: NetonHttpRequest): NetonHttpResponse
    fun stream(request: NetonHttpRequest): Flow<NetonHttpStreamChunk>
    suspend fun close()
}

data class NetonHttpRequest(
    val method: NetonHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: NetonHttpBody? = null,
    val timeout: NetonHttpTimeout? = null,  // overrides client default
    val metadata: Map<String, String> = emptyMap(),  // for logging/retry hooks
)

sealed interface NetonHttpBody {
    data class Json(val text: String) : NetonHttpBody                  // pre-serialized JSON string
    data class Text(val text: String, val contentType: String) : NetonHttpBody
    data class Bytes(val bytes: ByteArray, val contentType: String) : NetonHttpBody
}

data class NetonHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,  // for non-streaming; large body callers use stream()
)

sealed interface NetonHttpStreamChunk {
    data class Bytes(val bytes: ByteArray) : NetonHttpStreamChunk
    data class Text(val text: String) : NetonHttpStreamChunk
    data class End(val finalHeaders: Map<String, String>) : NetonHttpStreamChunk  // trailers if any
}

enum class NetonHttpMethod { Get, Post, Put, Delete, Patch, Head, Options }

data class NetonHttpTimeout(
    val connectMillis: Long? = null,
    val requestMillis: Long? = null,
    val socketMillis: Long? = null,
)
```

### 2.4 Typed error (`NetonHttpError`)

```kotlin
sealed interface NetonHttpError {
    val message: String
    val cause: Throwable?

    data class Network(override val message: String, override val cause: Throwable?) : NetonHttpError
    data class Timeout(override val message: String, override val cause: Throwable?) : NetonHttpError
    data class Http(val statusCode: Int, override val message: String, val body: String?) : NetonHttpError {
        override val cause: Throwable? = null
    }
    data class Unknown(override val message: String, override val cause: Throwable?) : NetonHttpError
}

class NetonHttpException(val error: NetonHttpError) : RuntimeException(error.message, error.cause)
```

Provider adapters in `neton-ai` map `NetonHttpError` → semantic `AiError` (e.g., `Http(429)` → `AiError.RateLimited`, `Http(401)` → `AiError.Unauthorized`).

### 2.5 SSE parser primitive

```kotlin
data class NetonSseEvent(
    val id: String? = null,
    val event: String? = null,    // "message_start", "content_block_delta", etc. (Anthropic uses event types)
    val data: String,             // raw data field; may be JSON or "[DONE]"
)

/** Stateful, line-by-line accumulator. Caller feeds lines from an SSE body. */
class NetonSseParser {
    fun accept(line: String): List<NetonSseEvent>  // 0 or 1 event per line; events finalize on blank line
    fun finish(): List<NetonSseEvent>              // flush any pending event at stream end
}

/** Convenience: line-flow → event-flow */
fun Flow<String>.parseSseEvents(): Flow<NetonSseEvent>
fun Flow<NetonHttpStreamChunk>.parseSseEvents(): Flow<NetonSseEvent>
```

**Must handle**:
- `data: <json>\n\n` (single-line JSON event)
- `data: <line1>\ndata: <line2>\n\n` (multi-line data field concatenated with `\n`)
- `event: <name>\ndata: <json>\n\n` (Anthropic event-typed)
- `id: <id>\nevent: ...\ndata: ...\n\n`
- `: comment line\n` (keep-alive comments, ignored)
- `data: [DONE]\n\n` (OpenAI end sentinel; parser passes through as data="[DONE]", consumer decides)
- Cross-chunk fragmentation: `data: {part_1` then `partial_2}\n\n` arriving in separate HTTP chunks
- Missing trailing newline at stream end (`finish()` flushes)

### 2.6 Cancellation propagation

When `Flow<NetonHttpStreamChunk>` collection is cancelled (parent coroutine cancelled, or `Flow.first()` / `Flow.take(N)` early-completed):

1. `CancellationException` propagates up through Flow operators
2. Internal collection coroutine cancelled (structured concurrency)
3. Underlying Ktor `HttpResponse.body` channel closed
4. Ktor engine closes the TCP connection
5. Server observes connection close → stops generating

**Test requirement**: PR0 has a `MockEngine`-based test that asserts `client.stream(...).take(1).collect { ... }` triggers `MockEngine` close signal. (Deep TCP behavior is verified manually in live smoke; not required in CI.)

### 2.7 Redaction policy

```kotlin
data class NetonRedactionPolicy(
    /** Header keys (case-insensitive) NEVER logged at any level, replaced with "<redacted>". */
    val redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS,
    /** When false, request/response body content is not logged (only length/sha hint). */
    val allowBodyLogging: Boolean = false,
)

val DEFAULT_REDACTED_HEADERS = setOf(
    "Authorization",
    "X-Api-Key",
    "api-key",
    "anthropic-api-key",
    "Cookie",
    "Set-Cookie",
    "Proxy-Authorization",
)
```

**Hard rule** (applies to all log levels, including TRACE):
- Headers in `redactedHeaders` are **never** printed in any form. No prefix, no suffix, no hash.
- Body content is only printed when `allowBodyLogging = true`, and even then sanitized (see §3.12).
- API keys **never** appear in logs, period. Not at TRACE. Not in error messages.

### 2.8 Retry policy primitive

```kotlin
interface NetonRetryPolicy {
    fun shouldRetry(attempt: Int, response: NetonHttpResponse?, error: NetonHttpError?): RetryDecision
}

sealed interface RetryDecision {
    data object DoNotRetry : RetryDecision
    data class RetryAfter(val delayMillis: Long) : RetryDecision
}

object NoRetryPolicy : NetonRetryPolicy {
    override fun shouldRetry(...) = RetryDecision.DoNotRetry
}
```

v0.1 ships only `NoRetryPolicy`. `neton-ai` does **not** rely on retry at HTTP layer (router fallback handles retry semantics at the AI layer); HTTP-layer retry is a primitive for future modules.

### 2.9 `NetonHttpClient` lifecycle (Neton Component)

```kotlin
object HttpClientComponent : NetonComponent<HttpClientConfig> { ... }
fun Neton.LaunchBuilder.httpClient(block: HttpClientConfig.() -> Unit) = install(HttpClientComponent, block)
```

(`HttpClientComponent` not `NetonHttpClientComponent` — match neighbor naming `RedisComponent` / `HttpComponent`.)

Config file: `config/http-client.conf` (TOML, camelCase per Neton convention; no `[http-client]` wrapper). v0.1 minimal config:

```toml
debug = false

[defaults]
connectMillis = 5000
requestMillis = 60000
socketMillis = 60000
```

### 2.10 `neton-http-client` module structure

```
neton-http-client/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/neton/http/client/
    │   ├── NetonHttpClient.kt
    │   ├── NetonHttpRequest.kt
    │   ├── NetonHttpResponse.kt
    │   ├── NetonHttpMethod.kt
    │   ├── NetonHttpBody.kt
    │   ├── NetonHttpStreamChunk.kt
    │   ├── NetonHttpTimeout.kt
    │   ├── NetonHttpError.kt
    │   ├── NetonHttpException.kt
    │   ├── NetonRedactionPolicy.kt
    │   ├── NetonRetryPolicy.kt
    │   ├── HttpClientConfig.kt
    │   ├── HttpClientComponent.kt
    │   ├── internal/
    │   │   ├── DefaultNetonHttpClient.kt
    │   │   └── KtorEngineFactory.kt      // expect fun defaultKtorEngine(): HttpClientEngineFactory<*>
    │   └── sse/
    │       ├── NetonSseEvent.kt
    │       ├── NetonSseParser.kt
    │       └── SseFlowOps.kt             // Flow<String>.parseSseEvents(), Flow<Chunk>.parseSseEvents()
    ├── macosMain/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.macos.kt    // actual = Darwin
    ├── linuxMain/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.linux.kt    // actual = CIO
    ├── mingwX64Main/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.mingw.kt    // actual = WinHttp
    └── commonTest/kotlin/neton/http/client/
        ├── NetonSseParserTest.kt
        ├── SseFlowOpsTest.kt
        ├── MockEngineHttpClientTest.kt
        └── CancellationTest.kt
```

---

## 3. `neton-ai` v0.1

### 3.1 Provider scope

| Provider | v0.1 | Adapter | Wire protocol |
|---|---|---|---|
| OpenAI | ✅ | `OpenAiCompatibleProvider` | `/v1/chat/completions`, `/v1/embeddings`; OpenAI SSE |
| DeepSeek | ✅ | `OpenAiCompatibleProvider` | OpenAI-compatible (base_url swap) |
| Qwen (DashScope compat mode) | ✅ | `OpenAiCompatibleProvider` | OpenAI-compatible (base_url swap) |
| OpenRouter | ✅ | `OpenAiCompatibleProvider` | OpenAI-compatible (base_url swap) |
| Anthropic Claude | ✅ | `AnthropicProvider` | `/v1/messages`, Anthropic event-typed SSE, tool_use/tool_result blocks |
| Gemini / Vertex AI | ❌ v0.2 | (deferred) | `generateContent`, non-standard streaming |

### 3.2 Core data types

Top-level types live in package `neton.ai`. Subsystems use subpackages:
- `neton.ai` — `AiClient`, `AiMessage`, `AiContent`, `AiRole`, `AiToolCall`, `AiToolResult`, `AiUsage`, `AiFinishReason`, `AiError`, `AiException`, `AiStreamEvent`, `AiModelId`, `AiToolDefinition`, `ToolChoice`, `GenerateTextRequest/Result`, `StreamTextRequest`, `EmbeddingRequest/Result`, `AiComponent`, `AiConfig`
- `neton.ai.builder` — DSL builders
- `neton.ai.provider` — SPI interfaces and provider-call types
- `neton.ai.adapter.openaicompatible` / `neton.ai.adapter.anthropic` — provider adapter implementations
- `neton.ai.routing` — router, policy, routing config
- `neton.ai.usage` — usage recorder interface + built-in impls
- `neton.ai.internal` — implementation details not part of public API

#### `neton.ai.AiMessage`

```kotlin
data class AiMessage(
    val role: AiRole,
    val content: List<AiContent> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class AiRole { System, User, Assistant, Tool }

sealed interface AiContent {
    data class Text(val text: String) : AiContent
    // v0.2 extension points (not implemented in v0.1; do NOT write stub variants):
    //   - ImageUrl(url: String, detail: String?)
    //   - ImageData(mediaType: String, base64: String)
    //   - Audio(...)
}
```

**Mapper responsibility**:
- OpenAi-compat mapper: 1:1 field correspondence to OpenAI chat messages
- Anthropic mapper: flattens `content_blocks` — `text` blocks → `content`; `tool_use` blocks → `toolCalls`; `tool_result` blocks (in user messages) → split into separate `role=Tool` messages with `toolCallId`

**Loss accepted**: text/tool_use interleaving order inside a single Anthropic assistant turn is not preserved. Acceptable for v0.1.

#### `neton.ai.AiToolCall` / `AiToolResult`

```kotlin
data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,    // raw JSON string from model (assertion: must be valid JSON object)
)

data class AiToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
    val format: AiToolResultFormat = AiToolResultFormat.Json,
)

enum class AiToolResultFormat { Json, Text }
```

**`String` rationale**: avoid leaking `kotlinx.serialization.json.JsonElement` to public API; matches OpenAI wire (which is already a JSON string for `tool_calls[i].function.arguments`); consumers free to parse with any library.

#### `neton.ai.AiUsage`

```kotlin
data class AiUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)
```

All fields nullable. Providers MAY report only some fields; AiClient does **not** auto-derive `totalTokens`. Usage recorders compute aggregates themselves.

v0.2 extension: `cacheReadInputTokens`, `cacheCreationInputTokens` (Anthropic prompt cache), `reasoningTokens` (o1-style).

#### `neton.ai.AiFinishReason`

```kotlin
enum class AiFinishReason {
    Stop,            // natural completion
    Length,          // max_tokens hit
    ToolCalls,       // model paused awaiting tool results (returned when tool loop exits)
    ContentFilter,   // safety filter
    Other,           // catch-all (do NOT leak provider-specific string)
}
```

Failure does **not** produce a `FinishReason.Error` variant; failures flow through `AiError` / `AiException`.

#### `neton.ai.AiError` + `AiException`

```kotlin
sealed interface AiError {
    val message: String
    val cause: Throwable?

    // Wire-level (fallback-eligible per §3.6)
    data class Network(override val message: String, override val cause: Throwable?) : AiError
    data class Timeout(override val message: String, override val cause: Throwable?) : AiError
    data class RateLimited(val retryAfterMillis: Long?, override val message: String) : AiError { override val cause: Throwable? = null }
    data class ServerError(val statusCode: Int, override val message: String) : AiError { override val cause: Throwable? = null }

    // Auth (NOT fallback-eligible)
    data class Unauthorized(override val message: String) : AiError { override val cause: Throwable? = null }
    data class Forbidden(override val message: String) : AiError { override val cause: Throwable? = null }

    // Provider-semantic (NOT fallback-eligible)
    data class InvalidRequest(override val message: String) : AiError { override val cause: Throwable? = null }
    data class ContextLengthExceeded(override val message: String) : AiError { override val cause: Throwable? = null }
    data class ContentFilter(override val message: String) : AiError { override val cause: Throwable? = null }
    data class ModelNotFound(val modelId: String, override val message: String) : AiError { override val cause: Throwable? = null }

    data class Unknown(override val message: String, override val cause: Throwable?) : AiError
}

/** Stable, refactor-safe error class name for telemetry. */
val AiError.kind: String
    get() = when (this) {
        is AiError.Network -> "Network"
        is AiError.Timeout -> "Timeout"
        is AiError.RateLimited -> "RateLimited"
        is AiError.ServerError -> "ServerError"
        is AiError.Unauthorized -> "Unauthorized"
        is AiError.Forbidden -> "Forbidden"
        is AiError.InvalidRequest -> "InvalidRequest"
        is AiError.ContextLengthExceeded -> "ContextLengthExceeded"
        is AiError.ContentFilter -> "ContentFilter"
        is AiError.ModelNotFound -> "ModelNotFound"
        is AiError.Unknown -> "Unknown"
    }

fun AiError.isFallbackEligible(): Boolean = when (this) {
    is AiError.Network -> true
    is AiError.Timeout -> true
    is AiError.RateLimited -> true                       // v0.1: always fallback; v0.2 may add wait-then-retry policy
    is AiError.ServerError -> statusCode in 500..599
    else -> false
}

class AiException(val error: AiError) : RuntimeException(error.message, error.cause)
```

### 3.3 Stream events (`AiStreamEvent`)

```kotlin
sealed interface AiStreamEvent {
    /** First event of any stream. Emitted by AiClient AFTER first provider event read successfully. */
    data class Started(val providerId: String, val modelName: String) : AiStreamEvent

    data class TextDelta(val text: String) : AiStreamEvent

    /** Fired when tool call id+name first observed. name may be null if provider sends id first. */
    data class ToolCallStarted(val id: String, val name: String?) : AiStreamEvent

    /** Argument JSON streamed in fragments (not necessarily valid JSON pieces). */
    data class ToolCallArgumentsDelta(val id: String, val argumentsFragment: String) : AiStreamEvent

    /** Tool call fully assembled; argumentsJson is valid JSON. */
    data class ToolCallReady(val call: AiToolCall) : AiStreamEvent

    /** Local executor finished. Only emitted when an executor was registered for this tool. */
    data class ToolResultReady(val result: AiToolResult) : AiStreamEvent

    /** Terminal success. Exactly one Completed OR Failed per stream (or zero if cancelled). */
    data class Completed(
        val message: AiMessage,
        val text: String,
        val usage: AiUsage?,
        val finishReason: AiFinishReason,
    ) : AiStreamEvent

    /** Terminal failure. */
    data class Failed(val error: AiError) : AiStreamEvent
}
```

**Stream contract**:
- `Started` is emitted by `AiClient`, NOT by providers (providers MUST NOT emit `Started`).
- Exactly one `Completed` or `Failed` per stream, unless the consumer cancels (then `CancellationException` propagates; **no** synthetic `Failed(Cancelled)`).
- In a tool-loop run, intermediate-round `Completed` events from providers are consumed internally; only the final-round `Completed` is propagated to the consumer.
- Tool-loop intermediate-round `Failed` events trigger end-of-stream `Failed` (no mid-stream provider switching after `Started`).

### 3.4 Public API: `AiClient`

```kotlin
package neton.ai

interface AiClient {
    // DSL builder variants
    suspend fun generateText(block: GenerateTextRequestBuilder.() -> Unit): GenerateTextResult
    fun streamText(block: StreamTextRequestBuilder.() -> Unit): Flow<AiStreamEvent>
    suspend fun embed(block: EmbeddingRequestBuilder.() -> Unit): EmbeddingResult

    // Request-object variants (for callers doing request preprocessing/caching)
    suspend fun generateText(request: GenerateTextRequest): GenerateTextResult
    fun streamText(request: StreamTextRequest): Flow<AiStreamEvent>
    suspend fun embed(request: EmbeddingRequest): EmbeddingResult
}
```

#### `GenerateTextRequest`

```kotlin
data class GenerateTextRequest(
    val model: AiModelId? = null,
    val modelPolicy: String? = null,
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val maxToolRounds: Int = 3,
    val metadata: Map<String, String> = emptyMap(),
)

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}
```

#### `StreamTextRequest`

**Independent `data class`, not a typealias** — reserved for future divergent fields (`includeUsage`, `heartbeatIntervalMillis`, `streamBufferPolicy`).

```kotlin
data class StreamTextRequest(
    val model: AiModelId? = null,
    val modelPolicy: String? = null,
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val maxToolRounds: Int = 3,
    val metadata: Map<String, String> = emptyMap(),
)
```

#### `GenerateTextResult`

```kotlin
data class GenerateTextResult(
    val text: String,                       // concatenation of all Text content in message
    val message: AiMessage,                 // final assistant message (from last round)
    val toolCalls: List<AiToolCall>,        // accumulated across all rounds
    val toolResults: List<AiToolResult>,    // accumulated across all rounds
    val usage: AiUsage?,                    // aggregated across rounds (null-safe sum, null if all rounds null)
    val finishReason: AiFinishReason,
    val providerId: String,                 // actual provider hit (post-fallback)
    val modelName: String,                  // actual model hit
    val rounds: Int,                        // tool-loop rounds executed (>= 1)
)
```

#### `EmbeddingRequest` / `EmbeddingResult`

```kotlin
data class EmbeddingRequest(
    val model: AiModelId,                    // embedding requires explicit model (no policy, no default)
    val input: List<String>,                 // batch of inputs
    val metadata: Map<String, String> = emptyMap(),
)

data class EmbeddingResult(
    val embeddings: List<FloatArray>,        // same order as request.input
    val usage: AiUsage?,
    val providerId: String,
    val modelName: String,
)
```

v0.1 embeddings: OpenAi-compatible providers only (`/v1/embeddings`). Anthropic does not offer a native embedding API.

### 3.5 SPI (provider adapter authorship surface)

```kotlin
package neton.ai.provider

/** Provider-call request: slim version of GenerateTextRequest with AiClient-level concerns stripped
 *  (model / modelPolicy / maxToolRounds). Provider operates on resolved model only. */
data class ProviderCallRequest(
    val messages: List<AiMessage>,
    val tools: List<AiToolDefinition>,
    val toolChoice: ToolChoice,
    val temperature: Double?,
    val maxTokens: Int?,
    val topP: Double?,
    val stopSequences: List<String>,
    val metadata: Map<String, String>,
)

/** One round of model interaction; if toolCalls non-empty, model paused awaiting tool results. */
data class ProviderCallResponse(
    val message: AiMessage,
    val text: String,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
)

data class ProviderEmbedRequest(
    val input: List<String>,
    val metadata: Map<String, String>,
)

data class ProviderEmbedResponse(
    val embeddings: List<FloatArray>,
    val usage: AiUsage?,
)

interface AiTextModel {
    val providerId: String
    val modelName: String
    suspend fun generate(request: ProviderCallRequest): ProviderCallResponse
}

interface AiStreamingTextModel : AiTextModel {
    /** Provider stream contract:
     *  - MUST NOT emit AiStreamEvent.Started (AiClient owns Started).
     *  - MUST NOT emit AiStreamEvent.ToolResultReady (AiClient owns local executor lifecycle).
     *  - MUST emit exactly one AiStreamEvent.Completed OR Failed, unless collection cancelled.
     *  - Errors thrown from this Flow (or AiException) trigger fallback IF before first event. */
    fun stream(request: ProviderCallRequest): Flow<AiStreamEvent>
}

interface AiEmbeddingModel {
    val providerId: String
    val modelName: String
    suspend fun embed(request: ProviderEmbedRequest): ProviderEmbedResponse
}

interface AiProvider {
    val id: String
    fun textModel(modelName: String): AiTextModel?            // null → model not supported
    fun embeddingModel(modelName: String): AiEmbeddingModel?  // null → not supported (Anthropic always null in v0.1)
}

interface ProviderRegistry {
    fun get(providerId: String): AiProvider?
    fun all(): Map<String, AiProvider>
}
```

### 3.6 Routing

```kotlin
package neton.ai.routing

data class AiModelId(val providerId: String, val modelName: String) {
    override fun toString(): String = "$providerId:$modelName"
    companion object {
        /** Split at FIRST ':'. Model name may contain '/', ':', '-', '.' (e.g., openrouter:anthropic/claude-sonnet-4.5). */
        fun parse(s: String): AiModelId {
            val idx = s.indexOf(':')
            require(idx > 0 && idx < s.length - 1) {
                "Invalid model id '$s', expected 'provider:model'"
            }
            return AiModelId(s.substring(0, idx), s.substring(idx + 1))
        }
    }
}

data class RoutingConfig(
    val defaultModel: AiModelId? = null,
    val policies: Map<String, ModelPolicy> = emptyMap(),
)

data class ModelPolicy(
    val prefer: List<AiModelId>,
    val fallback: List<AiModelId>,
)

interface ModelRouter {
    /** Resolution priority:
     *  1. explicitModel != null → [explicitModel] (NO fallback; explicit = exact)
     *  2. modelPolicy != null   → prefer + fallback (in order)
     *  3. defaultModel != null  → [defaultModel] (NO fallback)
     *  4. none → throws AiException(AiError.InvalidRequest("No model or modelPolicy configured"))
     *  
     *  Rule: callers needing fallback MUST use modelPolicy, never explicit model.
     */
    fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId>
}
```

### 3.7 Tool loop algorithm

#### Non-streaming `generateText`

```
candidates = router.resolve(request.model, request.modelPolicy)
require(candidates.isNotEmpty()) { throw AiException(AiError.InvalidRequest(...)) }

var lastError: AiError? = null
for (modelId in candidates) {
    val model = registry.get(modelId.providerId)?.textModel(modelId.modelName)
    if (model == null) { lastError = AiError.ModelNotFound(modelId.toString(), ...); continue }

    val workingMessages = request.messages.toMutableList()
    val accumulatedCalls = mutableListOf<AiToolCall>()
    val accumulatedResults = mutableListOf<AiToolResult>()
    val accumulatedUsage = mutableListOf<AiUsage?>()
    var lastResp: ProviderCallResponse? = null
    var fellBack = false

    for (round in 1..request.maxToolRounds) {
        val startMillis = currentTimeMillis()
        val resp = try {
            model.generate(request.toSpi(workingMessages))
        } catch (e: AiException) {
            val err = e.error
            // Fallback only at round 1, and only for fallback-eligible errors, and only if more candidates remain
            if (round == 1 && err.isFallbackEligible() && modelId != candidates.last()) {
                lastError = err
                recordUsage(modelId, null, round, request.metadata, startMillis, success = false, errorKind = err.kind, finishReason = null)
                fellBack = true
                break  // try next candidate
            }
            throw e  // bubble up
        }
        recordUsage(modelId, resp.usage, round, request.metadata, startMillis, success = true, errorKind = null, finishReason = resp.finishReason)
        accumulatedUsage += resp.usage
        lastResp = resp

        if (resp.toolCalls.isEmpty()) {
            // Final response — no tool calls requested
            return GenerateTextResult(
                text = resp.text,
                message = resp.message,
                toolCalls = accumulatedCalls,
                toolResults = accumulatedResults,
                usage = aggregateUsage(accumulatedUsage),
                finishReason = resp.finishReason,
                providerId = modelId.providerId,
                modelName = modelId.modelName,
                rounds = round,
            )
        }

        accumulatedCalls += resp.toolCalls
        workingMessages += resp.message

        // Execute tools locally (if executor registered)
        for (call in resp.toolCalls) {
            val def = request.tools.firstOrNull { it.name == call.name }
            if (def?.executor == null) {
                // No executor: return early, let caller handle tool calls
                return GenerateTextResult(
                    text = resp.text,
                    message = resp.message,
                    toolCalls = accumulatedCalls,
                    toolResults = accumulatedResults,
                    usage = aggregateUsage(accumulatedUsage),
                    finishReason = AiFinishReason.ToolCalls,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    rounds = round,
                )
            }
            val result = try {
                AiToolResult(call.id, def.executor.execute(call.argumentsJson), isError = false)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AiToolResult(call.id, e.message ?: "tool execution failed", isError = true)
            }
            accumulatedResults += result
            workingMessages += AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text(result.content)),
                toolCallId = call.id,
            )
        }
    }

    // maxToolRounds exhausted — return last response with ToolCalls finish reason (no fake message)
    if (lastResp != null) {
        return GenerateTextResult(
            text = lastResp.text,
            message = lastResp.message,
            toolCalls = accumulatedCalls,
            toolResults = accumulatedResults,
            usage = aggregateUsage(accumulatedUsage),
            finishReason = AiFinishReason.ToolCalls,
            providerId = modelId.providerId,
            modelName = modelId.modelName,
            rounds = request.maxToolRounds,
        )
    }
    // If fellBack, continue to next candidate
}

throw AiException(lastError ?: AiError.Unknown("No candidate model succeeded", null))
```

**`aggregateUsage(list: List<AiUsage?>): AiUsage?`**: sum non-null `inputTokens` field across non-null usages; same for `outputTokens`, `totalTokens`. Each field is the sum of available non-null values, or `null` if no entries provided that field. Returns `null` if list is empty OR if all entries are null. Returns `AiUsage(...)` otherwise.

#### Streaming `streamText`

Conceptually the same loop, expressed as a `Flow<AiStreamEvent>`:

```
emit-flow {
    candidates = router.resolve(...)
    require(candidates.isNotEmpty())
    
    for (modelId in candidates) {
        val model = registry.get(...)?.let { it.textModel(...) as? AiStreamingTextModel } 
        if (model == null) { lastError = ModelNotFound; continue }
        
        var startedEmitted = false
        var workingMessages = request.messages.toMutableList()
        
        round_loop@ for (round in 1..maxToolRounds) {
            val providerFlow = model.stream(request.toSpi(workingMessages))
            var firstEventRead = false
            var roundCompleted: AiStreamEvent.Completed? = null
            var roundFailed: AiStreamEvent.Failed? = null
            val roundCalls = mutableListOf<AiToolCall>()
            
            try {
                providerFlow.collect { event ->
                    if (!firstEventRead) {
                        firstEventRead = true
                        if (!startedEmitted) {
                            emit(AiStreamEvent.Started(modelId.providerId, modelId.modelName))
                            startedEmitted = true
                        }
                    }
                    when (event) {
                        is AiStreamEvent.Started -> error("Provider must not emit Started")
                        is AiStreamEvent.ToolResultReady -> error("Provider must not emit ToolResultReady (AiClient emits after local executor runs)")
                        is AiStreamEvent.Completed -> roundCompleted = event
                        is AiStreamEvent.Failed -> roundFailed = event
                        is AiStreamEvent.ToolCallReady -> { roundCalls += event.call; emit(event) }
                        is AiStreamEvent.TextDelta,
                        is AiStreamEvent.ToolCallStarted,
                        is AiStreamEvent.ToolCallArgumentsDelta -> emit(event)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                // Fallback only allowed if first event never read AND more candidates remain
                if (!startedEmitted && !firstEventRead && e.error.isFallbackEligible() && modelId != candidates.last()) {
                    lastError = e.error
                    continue  // try next candidate (this round_loop break)
                }
                // After Started, no fallback
                emit(AiStreamEvent.Failed(e.error))
                return@emit-flow
            }
            
            if (roundFailed != null) {
                emit(roundFailed)
                return@emit-flow
            }
            
            if (roundCompleted == null) error("Provider stream ended without Completed/Failed")
            
            if (roundCalls.isEmpty()) {
                emit(roundCompleted)
                return@emit-flow
            }
            
            // Tool round: execute locally
            workingMessages += roundCompleted.message
            for (call in roundCalls) {
                val def = request.tools.firstOrNull { it.name == call.name }
                if (def?.executor == null) {
                    // No executor: emit final Completed with finishReason=ToolCalls
                    emit(roundCompleted.copy(finishReason = AiFinishReason.ToolCalls))
                    return@emit-flow
                }
                val result = try {
                    AiToolResult(call.id, def.executor.execute(call.argumentsJson), isError = false)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    AiToolResult(call.id, e.message ?: "tool failed", isError = true)
                }
                emit(AiStreamEvent.ToolResultReady(result))
                workingMessages += AiMessage(AiRole.Tool, listOf(AiContent.Text(result.content)), toolCallId = call.id)
            }
            // Continue to next round; do NOT re-emit Started
        }
        
        // maxToolRounds exhausted
        if (lastResp != null) emit(AiStreamEvent.Completed(..., finishReason = AiFinishReason.ToolCalls))
        return@emit-flow
    }
    
    emit(AiStreamEvent.Failed(lastError ?: AiError.Unknown(...)))
}
```

(Pseudocode; final implementation uses `kotlinx.coroutines.flow.flow { ... }` builder.)

### 3.8 Cancellation & fallback semantics

**Cancellation**:
- Triggered by consumer cancelling the coroutine scope or completing Flow early (`take`, `first`)
- `CancellationException` propagates up Flow operators (Kotlin standard)
- AiClient's internal `collect` of provider Flow is cancelled (structured concurrency)
- Provider's HTTP body stream cancelled via `neton-http-client` cancellation propagation (§2.6)
- Provider HTTP connection closes; remote side stops generating
- Mid-tool-execution: executor coroutine cancelled (structured concurrency)
- **No synthetic `Failed(Cancelled)` event emitted**; `CancellationException` is normal coroutine control flow
- **No `AiError` variant for cancellation**; cancellation is not a failure

**Fallback rules** (single source of truth):

| Condition | Action |
|---|---|
| `explicitModel` set in request | No fallback. Single candidate only. |
| `modelPolicy` set in request | Try `policy.prefer` then `policy.fallback` in order. Fallback only on fallback-eligible errors (see `isFallbackEligible()`). |
| `defaultModel` only | No fallback. Single candidate only. |
| Non-streaming: round 1 failure | Fallback eligible. |
| Non-streaming: round ≥ 2 failure | NOT fallback eligible (state already accumulated). Throw. |
| Streaming: before first provider event | Fallback eligible. |
| Streaming: after first provider event | NOT fallback eligible. Emit `Failed`. |
| Auth error / semantic error | NOT fallback eligible regardless of position. |

### 3.9 `AiComponent` + DSL

```kotlin
package neton.ai

object AiComponent : NetonComponent<AiConfig> {
    override fun defaultConfig(): AiConfig = AiConfig()

    override suspend fun init(ctx: NetonContext, config: AiConfig) {
        // Explicit precondition check: neton-http-client must be installed first.
        val httpClient = ctx.getOrNull(NetonHttpClient::class)
            ?: throw AiException(AiError.InvalidRequest(
                "neton-http-client must be installed before neton-ai. " +
                "Add `httpClient { ... }` before `ai { ... }` in your Neton.run { ... } block."
            ))

        val effective = mergeWithFile(ctx, config)
        val errors = effective.validate()
        if (errors.isNotEmpty()) {
            throw AiException(AiError.InvalidRequest("Invalid AI config: ${errors.joinToString()}"))
        }

        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.ai")
        val registry = DefaultProviderRegistry.build(effective.providers, httpClient, log)
        val router = DefaultModelRouter(effective.routing)
        val recorder = effective.usage.recorder ?: NoopAiUsageRecorder

        val aiClient = DefaultAiClient(registry, router, recorder, log)
        ctx.bind(AiClient::class, aiClient)

        if (effective.debug) {
            log?.info("AI initialized", mapOf(
                "providers" to effective.providers.keys.toList(),
                "defaultModel" to effective.routing.defaultModel?.toString(),
                "policies" to effective.routing.policies.keys.toList(),
            ))
        }
    }

    private fun mergeWithFile(ctx: NetonContext, dsl: AiConfig): AiConfig {
        // file → DSL explicit override → built-in defaults
        // See §3.10 for merge details and provider field-level override
        ...
    }
}

fun Neton.LaunchBuilder.ai(block: AiConfig.() -> Unit) = install(AiComponent, block)
```

#### DSL

```kotlin
class AiConfig {
    internal val providers = mutableMapOf<String, ProviderSpec>()
    internal var routing: RoutingConfig = RoutingConfig()
    internal var usage: UsageConfig = UsageConfig()
    var debug: Boolean = false

    fun providers(block: ProvidersBuilder.() -> Unit) {
        ProvidersBuilder(providers).apply(block)
    }
    fun routing(block: RoutingBuilder.() -> Unit) {
        routing = RoutingBuilder().apply(block).build()
    }
    fun usage(block: UsageBuilder.() -> Unit) {
        usage = UsageBuilder().apply(block).build()
    }
    internal fun validate(): List<String> { /* see below */ }
}

class ProvidersBuilder(private val target: MutableMap<String, ProviderSpec>) {
    fun openAiCompatible(id: String, block: OpenAiCompatibleSpec.() -> Unit) {
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid provider id '$id'" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = OpenAiCompatibleSpec(id).apply(block)
    }
    fun anthropic(id: String, block: AnthropicSpec.() -> Unit) {
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid provider id '$id'" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = AnthropicSpec(id).apply(block)
    }
}

sealed interface ProviderSpec { val id: String }

/** All fields nullable to distinguish "not set in DSL" from "set to default value".
 *  Effective config applies defaults after file merge. */
class OpenAiCompatibleSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null            // NO default; must be set in DSL or file (validation requires)
    var apiKey: String? = null             // required
    var organization: String? = null       // optional
    var timeoutMillis: Long? = null        // default 60_000
    var defaultHeaders: Map<String, String>? = null  // default emptyMap
}

class AnthropicSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null            // default https://api.anthropic.com
    var apiKey: String? = null             // required
    var version: String? = null            // default 2023-06-01
    var beta: List<String>? = null         // default emptyList
    var timeoutMillis: Long? = null        // default 60_000
    var defaultHeaders: Map<String, String>? = null  // default emptyMap
}

class RoutingBuilder {
    var defaultModel: String? = null
    private val policies = mutableMapOf<String, ModelPolicy>()
    fun policy(name: String, block: PolicyBuilder.() -> Unit) {
        require(name.isNotBlank()) { "Policy name must not be blank" }
        require(name !in policies) { "Duplicate policy '$name'" }
        policies[name] = PolicyBuilder().apply(block).build()
    }
    internal fun build() = RoutingConfig(
        defaultModel = defaultModel?.let(AiModelId::parse),
        policies = policies.toMap(),
    )
}

class PolicyBuilder {
    private val prefer = mutableListOf<AiModelId>()
    private val fallback = mutableListOf<AiModelId>()
    fun prefer(modelId: String) { prefer += AiModelId.parse(modelId) }
    fun fallback(modelId: String) { fallback += AiModelId.parse(modelId) }
    internal fun build() = ModelPolicy(prefer.toList(), fallback.toList())
}

class UsageBuilder {
    var recorder: AiUsageRecorder? = null
    internal fun build() = UsageConfig(recorder)
}

data class UsageConfig(val recorder: AiUsageRecorder?)
```

#### `validate()` rules

- `providers` not empty
- Each `provider.id`: non-blank, matches `[a-zA-Z0-9._-]+`
- Each `OpenAiCompatibleSpec.apiKey`: non-blank
- Each `OpenAiCompatibleSpec.baseUrl`: non-blank AND starts with `http://` or `https://`
- Each `AnthropicSpec.apiKey`: non-blank
- `routing.defaultModel?.providerId` (if set): must exist in `providers`
- Each `policy`:
  - `prefer` not empty
  - All `prefer[i].providerId` and `fallback[i].providerId` exist in `providers`
  - Duplicates within `prefer + fallback` produce **warning** (not hard fail)

### 3.10 Config file format (`config/ai.conf`)

**Format**: TOML (per Neton convention).
**Field naming**: camelCase (matches existing Neton config files like `application.conf`).
**File name = namespace**: `ai.conf` content is the `ai.*` namespace; do **not** wrap in `[ai]`.

Example:

```toml
debug = false

[providers.openai]
type = "openAiCompatible"
baseUrl = "https://api.openai.com/v1"
apiKey = "${OPENAI_API_KEY}"
timeoutMillis = 60000

[providers.deepseek]
type = "openAiCompatible"
baseUrl = "https://api.deepseek.com"
apiKey = "${DEEPSEEK_API_KEY}"

[providers.qwen]
type = "openAiCompatible"
baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
apiKey = "${DASHSCOPE_API_KEY}"

[providers.anthropic]
type = "anthropic"
apiKey = "${ANTHROPIC_API_KEY}"
version = "2023-06-01"

[routing]
defaultModel = "openai:gpt-4o-mini"

[routing.policies.cheap]
prefer = ["deepseek:deepseek-chat"]
fallback = ["qwen:qwen-plus", "openai:gpt-4o-mini"]

[routing.policies.strong]
prefer = ["anthropic:claude-sonnet-4.5"]
fallback = ["openai:gpt-4o"]
```

#### Merge precedence (highest wins)

1. **DSL explicitly set fields** (non-null in spec; `providers { openAiCompatible("openai") { timeoutMillis = 120_000 } }`)
2. **File config** (`config/ai.conf` + `config/ai.{env}.conf`)
3. **Built-in defaults** (Anthropic baseUrl, default timeouts, etc.)

#### Provider merge granularity

Provider with same `id` in DSL and file: **field-level merge**. For each field, DSL value (if set) overrides file value; unset DSL fields take file values; remaining unset fields take built-in defaults.

Example:
- File defines `[providers.openai]` with `baseUrl = "https://api.openai.com/v1"` and `apiKey = "${OPENAI_API_KEY}"`
- DSL defines `openAiCompatible("openai") { timeoutMillis = 120_000 }`
- Effective: `baseUrl=file`, `apiKey=file`, `timeoutMillis=120000`

### 3.11 Usage Recorder

```kotlin
package neton.ai.usage

interface AiUsageRecorder {
    suspend fun record(event: AiUsageEvent)
}

data class AiUsageEvent(
    val requestId: String?,                 // from request.metadata["requestId"] or "traceId"; nullable
    val providerId: String,
    val modelName: String,
    val usage: AiUsage?,                    // nullable; may be partial (any field null)
    val round: Int,                         // 1-based; tool-loop round
    val requestMetadata: Map<String, String>,
    val timestampEpochMillis: Long,
    val durationMillis: Long,
    val finishReason: AiFinishReason?,      // nullable on failure
    val success: Boolean,
    val errorKind: String? = null,          // AiError.kind (stable string), null on success
)

object NoopAiUsageRecorder : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {}
}

/** Built-in convenience recorder. Logs only allowlisted metadata keys for safety. */
class LoggingAiUsageRecorder(
    private val logger: Logger,
    /** Only these keys from requestMetadata are merged into the log line. Override to expand. */
    val metadataAllowlist: Set<String> = DEFAULT_METADATA_ALLOWLIST,
) : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {
        val safeMetadata = event.requestMetadata.filterKeys { it in metadataAllowlist }
        logger.info("ai.usage", mapOf(
            "requestId" to event.requestId,
            "provider" to event.providerId,
            "model" to event.modelName,
            "round" to event.round,
            "inputTokens" to event.usage?.inputTokens,
            "outputTokens" to event.usage?.outputTokens,
            "totalTokens" to event.usage?.totalTokens,
            "durationMs" to event.durationMillis,
            "finishReason" to event.finishReason?.name,
            "success" to event.success,
            "errorKind" to event.errorKind,
        ) + safeMetadata)
    }
}

val DEFAULT_METADATA_ALLOWLIST: Set<String> = setOf(
    "requestId", "traceId", "userId", "businessTag", "channelId",
)
```

**Recorder is called from `AiClient`** at each tool-loop round (success or fallback-eligible failure that's about to fall back). Final-round failure that's NOT fallback-eligible also produces one record before throwing. Cancellation: no record (cancellation is not a business event).

**`requestMetadata` is caller-sanitized**. Callers MUST NOT put user prompt content, raw tokens, PII into `request.metadata`. Built-in `LoggingAiUsageRecorder` defensively filters to allowlist; custom recorders are responsible for their own safety.

### 3.12 Logging & redaction

**Logger naming**:
- `neton.ai` — top-level (init, lifecycle)
- `neton.ai.router` — router decisions (model resolution, fallback choices)
- `neton.ai.provider.openAiCompatible.<id>` — per OpenAi-compat provider instance
- `neton.ai.provider.anthropic.<id>` — per Anthropic provider instance
- `neton.ai.client` — AiClient request dispatch
- `neton.ai.tool` — tool execution

**Per-level content**:

| Level | What's logged |
|---|---|
| ERROR | Errors with `AiError.kind`, no prompt content, no API key |
| WARN | Fallback decisions, retry hints, deprecated config usage |
| INFO | Lifecycle (init/shutdown), request start/finish (provider/model/duration/tokens), no body |
| DEBUG | Round details, tool call names/ids (NOT arguments content), no body |
| TRACE | Sanitized request/response body (see below); never headers in redaction list |

**Redaction (hard rules, all levels)**:
- Headers in `redactedHeaders` list (`Authorization`, `X-Api-Key`, `api-key`, `anthropic-api-key`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`): **never** printed in any form
- Body content default off; even at TRACE, body is sanitized:
  - Default v0.1 sanitizer: print `{...truncated, len=N, sha256=XX}` instead of body content
  - User message / assistant message / tool argument / tool result content: **never** in body log without explicit opt-in via `NetonRedactionPolicy(allowBodyLogging = true)` at the http-client layer

**Result**: prompt content, tool arguments, tool results, API keys NEVER appear in default Neton logs. Local-dev opt-in requires explicit config change.

### 3.13 `neton-ai` module structure

```
neton-ai/
├── build.gradle.kts
└── src/
    ├── commonMain/kotlin/neton/ai/
    │   ├── AiClient.kt
    │   ├── AiComponent.kt
    │   ├── AiConfig.kt
    │   ├── AiException.kt
    │   ├── AiModelId.kt
    │   ├── AiContent.kt
    │   ├── AiMessage.kt
    │   ├── AiRole.kt
    │   ├── AiToolCall.kt
    │   ├── AiToolResult.kt
    │   ├── AiToolDefinition.kt
    │   ├── AiUsage.kt
    │   ├── AiFinishReason.kt
    │   ├── AiError.kt
    │   ├── AiStreamEvent.kt
    │   ├── GenerateTextRequest.kt
    │   ├── GenerateTextResult.kt
    │   ├── StreamTextRequest.kt
    │   ├── EmbeddingRequest.kt
    │   ├── EmbeddingResult.kt
    │   ├── ToolChoice.kt
    │   ├── builder/
    │   │   ├── GenerateTextRequestBuilder.kt
    │   │   ├── StreamTextRequestBuilder.kt
    │   │   ├── EmbeddingRequestBuilder.kt
    │   │   └── AiToolDefinitionBuilder.kt
    │   ├── provider/
    │   │   ├── AiProvider.kt
    │   │   ├── AiTextModel.kt
    │   │   ├── AiStreamingTextModel.kt
    │   │   ├── AiEmbeddingModel.kt
    │   │   ├── ProviderCallRequest.kt
    │   │   ├── ProviderCallResponse.kt
    │   │   ├── ProviderEmbedRequest.kt
    │   │   ├── ProviderEmbedResponse.kt
    │   │   └── ProviderRegistry.kt
    │   ├── routing/
    │   │   ├── ModelRouter.kt
    │   │   ├── ModelPolicy.kt
    │   │   └── RoutingConfig.kt
    │   ├── usage/
    │   │   ├── AiUsageRecorder.kt
    │   │   ├── AiUsageEvent.kt
    │   │   ├── NoopAiUsageRecorder.kt
    │   │   └── LoggingAiUsageRecorder.kt
    │   ├── internal/
    │   │   ├── DefaultAiClient.kt
    │   │   ├── DefaultProviderRegistry.kt
    │   │   ├── DefaultModelRouter.kt
    │   │   ├── ToolLoop.kt
    │   │   ├── StreamingToolLoop.kt
    │   │   └── UsageAggregator.kt
    │   └── adapter/
    │       ├── openaicompatible/
    │       │   ├── OpenAiCompatibleProvider.kt
    │       │   ├── OpenAiCompatibleTextModel.kt
    │       │   ├── OpenAiCompatibleEmbeddingModel.kt
    │       │   ├── OpenAiCompatibleStreamMapper.kt
    │       │   ├── OpenAiCompatibleRequestMapper.kt
    │       │   ├── OpenAiCompatibleResponseMapper.kt
    │       │   └── dto/  (internal kotlinx.serialization wire DTOs)
    │       └── anthropic/
    │           ├── AnthropicProvider.kt
    │           ├── AnthropicTextModel.kt
    │           ├── AnthropicStreamMapper.kt
    │           ├── AnthropicRequestMapper.kt
    │           ├── AnthropicResponseMapper.kt
    │           └── dto/  (internal kotlinx.serialization wire DTOs)
    └── commonTest/kotlin/neton/ai/
        ├── AiModelIdTest.kt
        ├── DefaultModelRouterTest.kt
        ├── ToolLoopTest.kt
        ├── StreamingToolLoopTest.kt
        ├── UsageAggregatorTest.kt
        ├── builder/  (DSL builder tests)
        ├── adapter/openaicompatible/  (mapper + SSE tests)
        ├── adapter/anthropic/  (mapper + SSE tests)
        ├── fixtures/  (recorded provider responses, sanitized)
        └── usage/  (recorder tests)
```

---

## 4. Provider Adapter Specifications

### 4.1 `OpenAiCompatibleProvider`

**Targets**: OpenAI, DeepSeek, Qwen (DashScope compat mode), OpenRouter, any base_url-swappable OpenAI-compat endpoint.

**Protocol scope (v0.1)**: This provider targets the **Chat Completions** compatible protocol (`/chat/completions` + `/embeddings`) ONLY. OpenAI's newer **Responses API** (`/v1/responses` with its own streaming event taxonomy used by o1/o-series reasoning models) is **NOT** in v0.1 scope. A future `OpenAiResponsesProvider` adapter may be added in v0.2.

**Endpoints used**:
- `POST {baseUrl}/chat/completions` — generate + stream
- `POST {baseUrl}/embeddings` — embed

**Headers**:
- `Authorization: Bearer {apiKey}`
- `Content-Type: application/json`
- `OpenAI-Organization: {organization}` (if set)
- Plus `defaultHeaders` from config

**Request mapping** (`AiMessage` → OpenAI `messages`):
- `role=System` → `role: "system"`
- `role=User` → `role: "user"`
- `role=Assistant` with `toolCalls` → `role: "assistant", tool_calls: [...]`
- `role=Tool` → `role: "tool", tool_call_id: ..., content: ...`
- `content[].Text` → `content: string` (concatenated) or `content: [{type:"text", text:...}]`

**Tool mapping**:
- `AiToolDefinition` → `{type: "function", function: {name, description, parameters: <inputSchemaJson>}}`
- `ToolChoice.Auto` → `tool_choice: "auto"`
- `ToolChoice.None` → `tool_choice: "none"`
- `ToolChoice.Required` → `tool_choice: "required"`
- `ToolChoice.Named(n)` → `tool_choice: {type: "function", function: {name: n}}`

**SSE handling**:
- Endpoint: `chat/completions` with `stream: true`
- Stream framing: `data: <json>\n\n` followed by `data: [DONE]\n\n`
- Deltas: `choices[0].delta.content` (text), `choices[0].delta.tool_calls[i]` (incremental id/name/function.arguments)
- Tool call assembly: index-keyed; first delta with `id` + `function.name` → `ToolCallStarted`; subsequent `function.arguments` fragments → `ToolCallArgumentsDelta`; on stream end or `finish_reason="tool_calls"` → `ToolCallReady` for each accumulated call
- Final event: `choices[0].finish_reason` → `Completed.finishReason`; optional `usage` (in `stream_options.include_usage` mode) → `Completed.usage`

**`finish_reason` mapping**:
- `"stop"` → `AiFinishReason.Stop`
- `"length"` → `AiFinishReason.Length`
- `"tool_calls"` → `AiFinishReason.ToolCalls`
- `"function_call"` (legacy) → `AiFinishReason.ToolCalls`
- `"content_filter"` → `AiFinishReason.ContentFilter`
- other / null → `AiFinishReason.Other`

**Error mapping**:
- HTTP 401 → `AiError.Unauthorized`
- HTTP 403 → `AiError.Forbidden`
- HTTP 429 → `AiError.RateLimited(retryAfterMillis = parseRetryAfter(headers))`
- HTTP 400 with `error.code = "context_length_exceeded"` → `AiError.ContextLengthExceeded`
- HTTP 400 other → `AiError.InvalidRequest`
- HTTP 404 with `error.code = "model_not_found"` → `AiError.ModelNotFound`
- HTTP 5xx → `AiError.ServerError(statusCode)`
- Network failure → `AiError.Network` (from `NetonHttpError.Network`)
- Timeout → `AiError.Timeout`
- Other → `AiError.Unknown`

### 4.2 `AnthropicProvider`

**Endpoints used**:
- `POST {baseUrl}/v1/messages` — generate + stream (single endpoint, `stream: true` toggle)

**Headers**:
- `x-api-key: {apiKey}` (Anthropic-specific; NOT `Authorization: Bearer`)
- `anthropic-version: {version}` (default `2023-06-01`)
- `anthropic-beta: {beta.join(",")}` (if beta features set)
- `Content-Type: application/json`
- Plus `defaultHeaders` from config

**Request mapping** (`AiMessage` → Anthropic `messages`):
- System messages: merged into top-level `system: string` field (Anthropic does not have a system role in messages array)
- `role=User` → `{role: "user", content: [...]}`
- `role=Assistant` → `{role: "assistant", content: [...]}`
- `role=Tool` → wrapped into `{role: "user", content: [{type: "tool_result", tool_use_id: <toolCallId>, content: <text>}]}`
- `AiContent.Text` → `{type: "text", text: ...}`
- `AiToolCall` in assistant message → content block `{type: "tool_use", id, name, input: <parsed argumentsJson>}`

**Tool mapping**:
- `AiToolDefinition` → `{name, description, input_schema: <inputSchemaJson>}` (Anthropic uses `input_schema`, OpenAI uses `parameters`)
- `ToolChoice.Auto` → `tool_choice: {type: "auto"}`
- `ToolChoice.None` → omit tools from request
- `ToolChoice.Required` → `tool_choice: {type: "any"}`
- `ToolChoice.Named(n)` → `tool_choice: {type: "tool", name: n}`

**SSE handling**:

Anthropic SSE is **event-typed**: `event: <name>\ndata: <json>\n\n`. Each event has a typed `data` payload schema. The canonical event sequence for a successful response is:

```
message_start
( content_block_start  content_block_delta*  content_block_stop )+   # one tuple per content block
message_delta+
message_stop
```

Plus any number of `ping` events interleaved anywhere, plus possible `error` events at any point. Per Anthropic docs, **new event types may be added in the future**; mapper MUST tolerate unknown events.

**Per-event mapping rules**:

| Anthropic event | Mapper action |
|---|---|
| `message_start` | Initialize accumulators: capture `message.id`, `message.model`, initial `usage` (has `input_tokens`, `output_tokens=0`). Initialize `contentBlocks: Map<Int, BlockAccumulator>` keyed by `content_block` index. **No `AiStreamEvent` emitted.** |
| `content_block_start` with `content_block.type="text"` | Initialize `BlockAccumulator(type=Text)` at given index. **No event emitted.** |
| `content_block_start` with `content_block.type="tool_use"` | Initialize `BlockAccumulator(type=ToolUse, id=content_block.id, name=content_block.name, inputBuffer="")` at given index. Emit `AiStreamEvent.ToolCallStarted(id=content_block.id, name=content_block.name)`. |
| `content_block_delta` with `delta.type="text_delta"` | Append `delta.text` to text accumulator at given index. Emit `AiStreamEvent.TextDelta(delta.text)`. |
| `content_block_delta` with `delta.type="input_json_delta"` | Append `delta.partial_json` to **per-index `inputBuffer`** of the corresponding tool_use block. Emit `AiStreamEvent.ToolCallArgumentsDelta(id=block.id, argumentsFragment=delta.partial_json)`. **Do NOT parse partial JSON; accumulate as raw string.** |
| `content_block_stop` for a tool_use block | Parse accumulated `inputBuffer` as JSON (if `inputBuffer.isBlank()`, treat as `"{}"`). On parse failure: emit `AiStreamEvent.Failed(AiError.Unknown("Invalid tool input JSON from Anthropic: ${err}"))` and end stream. On success: emit `AiStreamEvent.ToolCallReady(AiToolCall(id=block.id, name=block.name, argumentsJson=inputBuffer))`. |
| `content_block_stop` for a text block | No event emitted. |
| `message_delta` | Read `delta.stop_reason`, `delta.stop_sequence` if present. Read `usage` — **`message_delta.usage` field values are CUMULATIVE token counts, not increments**. On each `message_delta`, **replace** current usage with the new cumulative values (preserving `input_tokens` from `message_start` if not re-stated). **No event emitted yet.** |
| `message_stop` | Emit `AiStreamEvent.Completed(message, text, usage, finishReason)` with: `message`=AiMessage assembled from accumulated text content + tool_use blocks → `toolCalls`; `text`=concatenation of text accumulators; `usage`=last cumulative usage observed; `finishReason`=mapped from `stop_reason`. End stream. |
| `ping` | Ignore. |
| `error` | Read `error.type` and `error.message`. Map to `AiError` (see Error mapping below). Emit `AiStreamEvent.Failed(error)`. End stream. |
| **unknown event type** | **Log at DEBUG and ignore.** MUST NOT fail the stream. |

**Accumulator invariants**:
- `BlockAccumulator` keyed by content_block index (integer). Multiple parallel tool_use blocks may be in flight (different indexes); deltas MUST be routed to the correct index.
- Text deltas and tool_use input_json_deltas can interleave across blocks; mapper handles each independently.

**`stop_reason` mapping**:
- `"end_turn"` → `AiFinishReason.Stop`
- `"max_tokens"` → `AiFinishReason.Length`
- `"tool_use"` → `AiFinishReason.ToolCalls`
- `"stop_sequence"` → `AiFinishReason.Stop`
- other / null → `AiFinishReason.Other`

**Usage handling (non-stream `/v1/messages`)**:
- Response body has `usage.input_tokens` and `usage.output_tokens` at top level.
- Map to `AiUsage(inputTokens, outputTokens, totalTokens = null)`. Anthropic does not provide a server-side `total_tokens` field; do not auto-compute (per §3.2 nullable rule).

**`stop_reason` mapping**:
- `"end_turn"` → `AiFinishReason.Stop`
- `"max_tokens"` → `AiFinishReason.Length`
- `"tool_use"` → `AiFinishReason.ToolCalls`
- `"stop_sequence"` → `AiFinishReason.Stop`
- other / null → `AiFinishReason.Other`

**Error mapping**:
- HTTP 401 → `AiError.Unauthorized`
- HTTP 403 → `AiError.Forbidden`
- HTTP 429 → `AiError.RateLimited(retryAfterMillis = parseRetryAfter(headers))`
- HTTP 400 with `error.type = "invalid_request_error"` and message contains "context length" → `AiError.ContextLengthExceeded`
- HTTP 400 other → `AiError.InvalidRequest`
- HTTP 404 → `AiError.ModelNotFound`
- HTTP 5xx → `AiError.ServerError(statusCode)`
- Anthropic SSE `event: error` → map based on `error.type`
- Other → `AiError.Unknown`

**Embedding**: NOT supported (return `null` from `embeddingModel(...)`).

---

## 5. PR Plan

**Delivery model**: 4 internal milestones (PR0–PR3), but **v0.1 publicly "ready" only when PR3 merges**. Business consumers see no half-baked state.

### PR0: `neton-http-client` minimal infrastructure

**Scope**:
- Module skeleton, `build.gradle.kts`, KMP targets
- `NetonHttpClient` interface + `DefaultNetonHttpClient` impl
- Per-platform `KtorEngineFactory` (macOS Darwin / Linux CIO / Windows WinHTTP)
- `NetonHttpRequest` / `NetonHttpResponse` / `NetonHttpStreamChunk` / `NetonHttpMethod` / `NetonHttpBody` / `NetonHttpTimeout`
- `NetonHttpError` + `NetonHttpException`
- `NetonRedactionPolicy` + default redacted headers list
- `NetonRetryPolicy` + `NoRetryPolicy`
- `NetonSseEvent` + `NetonSseParser` + `Flow.parseSseEvents()`
- `HttpClientComponent` + `HttpClientConfig` + DSL
- Tests: SSE parser (full edge case suite), MockEngine request/response, MockEngine streaming, cancellation signal

**Acceptance**:
- All KMP targets compile (macosArm64 / macosX64 / linuxX64 / linuxArm64 / mingwX64)
- All unit tests pass
- `Neton.run { httpClient { ... } }` smoke test binds `NetonHttpClient` to context

### PR1: `neton-ai` core + non-streaming `generateText`

**Scope**:
- Module skeleton, `build.gradle.kts` (depends on `neton-http-client` + `neton-core` + `neton-logging`)
- All core types (§3.2): `AiMessage` / `AiContent` / `AiRole` / `AiToolCall` / `AiToolResult` / `AiUsage` / `AiFinishReason` / `AiError` + `kind` + `isFallbackEligible` / `AiException` / `AiModelId` / `AiToolDefinition` / `ToolChoice`
- Public API: `AiClient` interface, `GenerateTextRequest` / `Result`, `EmbeddingRequest` / `Result` (interface only, embed impl in PR3), DSL builders
- SPI (§3.5): `AiProvider`, `AiTextModel`, `AiEmbeddingModel`, `ProviderCallRequest` / `Response`, `ProviderRegistry`
- Routing (§3.6): `ModelRouter`, `RoutingConfig`, `ModelPolicy`, `DefaultModelRouter`
- `AiComponent` + `AiConfig` DSL (§3.9) — providers + routing only, usage stubbed to `NoopAiUsageRecorder`
- `OpenAiCompatibleProvider` — non-streaming generate only; tool calls returned, but loop in AiClient
- `AnthropicProvider` — non-streaming generate only
- Non-streaming tool loop in `DefaultAiClient` (§3.7 non-streaming version)
- `AiUsageRecorder` interface + `NoopAiUsageRecorder` + `LoggingAiUsageRecorder` (§3.11)
- Tests: model id parsing, router resolution + fallback rules, mapper round-trip via recorded fixtures, tool loop scripted-provider scenarios, AiComponent init failure cases

**Acceptance**:
- Compile + tests pass
- DSL example produces correct `GenerateTextRequest`
- Scripted provider verifies all tool loop branches (no executor, executor success, executor failure, maxToolRounds exhausted, fallback-eligible error round 1, no-fallback after round 1)

### PR2: `neton-ai` `streamText` + tool loop on stream

**Scope**:
- `AiStreamEvent` (§3.3) all 8 variants
- `StreamTextRequest` + DSL builder
- `AiStreamingTextModel` SPI
- `OpenAiCompatibleStreamMapper` — SSE → `Flow<AiStreamEvent>` (NOT emitting Started)
- `AnthropicStreamMapper` — Anthropic event-typed SSE → `Flow<AiStreamEvent>`
- Streaming tool loop in `DefaultAiClient` (§3.7 streaming version): emits Started after first provider event, fallback only before first event, multi-round tool execution
- Cancellation propagation tests
- Tests: stream mapper (recorded fixtures for both providers), streaming tool loop scripted scenarios (multi-round + cancellation + Started timing + post-Started failure)

**Acceptance**:
- Compile + tests pass
- Stream cancellation closes underlying mock engine connection
- Started emitted exactly once per stream, after first provider event
- No fallback after Started emitted

### PR3: `neton-ai` routing polish + embedding + examples + docs

**Scope**:
- `embed(...)` impl for `OpenAiCompatibleProvider`
- Usage recorder integration: record per round in non-streaming and streaming paths (success + fallback-eligible failure)
- Aggregate usage in `GenerateTextResult.usage`
- `examples/neton-ai-sample/` — `main()` demonstrating generateText / streamText with tool / embed; env-var-driven API keys; README
- README for `neton-ai` and `neton-http-client`
- Integration smoke task: `:examples:neton-ai-sample:runOpenAiSmoke`, `:runAnthropicSmoke` (manual, gated by env var presence)

**Acceptance**:
- All v0.1 acceptance gates (§6) pass
- Example runs end-to-end against live OpenAI + Anthropic (manual verification)
- Public docs (README) cover: install, DSL example, supported providers, supported model id formats, fallback rules

### Out of scope for v0.1 (deferred / future)

- Gemini / Vertex AI provider (v0.2)
- Auto JSON Schema generation from `@Serializable` (v0.2 or separate module)
- Migration of `neton-storage` and `privchat-client` to use `neton-http-client` (follow-up)
- Multimodal content (`AiContent.ImageUrl` / `ImageData`) (v0.2)
- Anthropic prompt cache token reporting (v0.2)
- `streamObject` / structured output beyond basic JSON mode (v0.2)
- `AiUsageRecorder` database-backed impl (in business layer, not framework)

---

## 6. Acceptance Gates (v0.1 ready)

Must all pass before declaring `neton-ai v0.1 ready`:

### 6.1 Build & test

1. ✅ All KMP targets compile (`./gradlew :neton-http-client:build :neton-ai:build`)
2. ✅ All unit tests pass (`./gradlew :neton-http-client:allTests :neton-ai:allTests`)
3. ✅ Recorded-fixture tests cover OpenAi-compat + Anthropic for: chat non-stream, chat stream, tool call non-stream, tool call stream, embedding (OpenAi-compat only)
4. ✅ SSE parser handles all edge cases listed in §2.5
5. ✅ Tool loop tests cover: no executor, executor success, executor failure, maxToolRounds exhausted, fallback (round 1), no fallback (round ≥ 2)
6. ✅ Stream tool loop tests cover: multi-round, cancellation, Started timing, post-Started failure
7. ✅ Router tests cover: explicit (no fallback), policy (prefer → fallback), default, missing config → InvalidRequest
8. ✅ Usage recorder invoked correctly: success records, fallback-eligible failure records, cancellation does NOT record
9. ✅ `Neton.run { httpClient {} ai {} }` example app boots successfully
10. ✅ Live smoke run completes for OpenAI + Anthropic (manual, with API keys)

### 6.2 API hygiene

11. ✅ No `io.ktor.*` imports leak into `neton.ai` public API (lint or visual review)
12. ✅ No `kotlinx.serialization.json.JsonElement` in `neton.ai` public API
13. ✅ Logging redaction verified: API keys never appear in default log output; sensitive headers replaced with `<redacted>`

### 6.3 Hard contract gates (must have explicit tests)

These are non-negotiable invariants; each MUST have at least one targeted test asserting the behavior:

14. ✅ **Anthropic unknown SSE event type** is logged at DEBUG and ignored (stream continues, no `Failed` event emitted). Test: feed `event: future_event_we_do_not_know\ndata: {}\n\n` mid-stream; assert no failure.
15. ✅ **Anthropic `ping` events** are silently ignored (no `AiStreamEvent` emitted). Test: feed `ping` between deltas; assert event sequence unchanged.
16. ✅ **Anthropic `error` events** map to `AiStreamEvent.Failed(AiError.*)` and terminate the stream. Test: feed `event: error\ndata: {"type":"error","error":{"type":"overloaded_error","message":"..."}}` ; assert `Failed` emitted and stream ends.
17. ✅ **Anthropic `input_json_delta` accumulation is per content block index**. Test: interleaved `input_json_delta` for `index=0` and `index=1` (two parallel tool_use blocks); assert each `ToolCallReady` receives only its own block's accumulated JSON.
18. ✅ **Anthropic `message_delta.usage` treated as cumulative, not incremental**. Test: feed two `message_delta` events with `usage.output_tokens = 10` then `usage.output_tokens = 25`; assert final `Completed.usage.outputTokens == 25` (not 35).
19. ✅ **`OpenAiCompatibleProvider` v0.1 targets Chat Completions only**, not Responses API. Documented in §4.1 and verified by code review (no `/v1/responses` endpoint usage, no Responses-event-type handling).
20. ✅ **Provider stream MUST NOT emit `AiStreamEvent.Started` or `AiStreamEvent.ToolResultReady`**. Test: scripted streaming provider that erroneously emits `Started`; assert `AiClient` raises `IllegalStateException` (contract violation, fail-fast).
21. ✅ **AiClient emits `Started` ONLY after first provider event is successfully read** (not at stream subscription). Test: scripted provider that throws before any event; assert no `Started` emitted, fallback succeeds; scripted provider that emits one `TextDelta` then throws; assert `Started` + `TextDelta` + `Failed` (no fallback after Started).

---

## 7. Test Strategy Summary

| Layer | Tool | Tests |
|---|---|---|
| HTTP client | Ktor `MockEngine` | Request mapping, response mapping, streaming chunks, cancellation signal |
| SSE parser | Pure unit (no engine) | Single line, multi-line, cross-chunk, comments, [DONE], missing trailing newline |
| Provider mappers | Recorded fixtures + assertion DSL | Round-trip for non-stream + stream; sanitized API keys; cover all event/error variants |
| Tool loop | Scripted mock provider | All branches per §3.7 algorithm |
| Streaming tool loop | Scripted mock streaming provider | Multi-round, cancellation, Started timing |
| Router | Pure unit | All resolution rules per §3.6 |
| AiComponent | Stub `NetonContext` | Bind success, missing http-client failure, invalid config failure |
| Example app | Manual gradle task | Live smoke against real APIs (env-var gated) |

**CI policy**: No real network in CI. Even if API key env vars are present, live smoke tasks must be explicitly invoked (`:examples:neton-ai-sample:runOpenAiSmoke`), never auto-run.

**Fixture policy**: Fixtures are minimized — only provider response bodies (JSON / SSE) saved; no full prompts, no user content, no real headers. API keys removed (replaced with `sk-test-xxx`). Each fixture's source URL + date recorded as comment.

---

## 8. Cross-Cutting Decisions Reference

| Decision | Source | Rationale |
|---|---|---|
| Module B (new `neton-http-client`) chosen over A (embed in ai) or C (rename neton-http) | brainstorming round | Avoid 3rd repeat of Ktor Client setup; future-friendly |
| Provider scope Z (OpenAi-compat + Anthropic; defer Gemini) | brainstorming round | OpenAi-compat covers 3 of 5; Anthropic critical; Gemini wire complexity defers to v0.2 |
| Approach A (single-layer SPI) over B (transport+semantic) | Section 1 | vercel-ai validates A for 25+ providers; v0.1 only 2 providers don't justify B |
| Capability-split SPI (`AiTextModel` + `AiStreamingTextModel`) | Section 1 | Future-proof for non-streaming or non-text providers |
| α `AiMessage` (separate fields) over β (content blocks) | Section 2.1 | OpenAi-compat zero-cost mapping; Anthropic flatten acceptable |
| `String` for tool args/results, not `JsonElement` | Section 2.3 | No kotlinx.serialization leak; matches OpenAI wire |
| 8 `AiStreamEvent` variants, `ToolCallStarted.name` nullable | Section 2.4 | Allow streaming providers that send id before name |
| All `AiUsage` fields nullable | Section 2.5 | Providers vary; don't auto-derive total |
| `AiError.kind` stable string for telemetry | Section 4.4 | Refactor-safe (vs class.simpleName) |
| Started emitted by AiClient, not provider | Section 3.2 | Cleanly defines fallback boundary; provider doesn't know if it's the final candidate |
| Explicit model = no fallback; need fallback ⇒ use modelPolicy | Section 3.3 | Predictable; explicit is exact |
| `AiException(error: AiError)` for cross-method exception | Section 3.5 | sealed AiError is not Throwable; AiException is the carrier |
| Cancellation does NOT emit `Failed(Cancelled)` | Section 3.6 | CancellationException is Kotlin control flow, not business event |
| TOML + camelCase for `ai.conf` | Section 4.3 (verified) | Matches existing Neton config files |
| File → DSL explicit → built-in defaults merge | Section 4.3 | Predictable precedence |
| Provider field-level merge for same id | Section 4.3 | DSL can override single field without restating all |
| API keys NEVER in logs (any level) | Section 4.6 | Hard security rule |

---

## 9. Open Items / Future Work

- **JSON Schema generator**: `inputSchema<T>()` typed DSL would benefit from automatic JSON Schema generation from `@Serializable` Kotlin classes. v0.1 punts: typed tool DSL only does codec; caller passes `inputSchemaJson` explicitly. Future: investigate `kotlinx-serialization-json-schema` or build minimal in-house generator.
- **HTTP retry**: `NetonRetryPolicy` interface ships in PR0 but no implementation beyond `NoRetryPolicy`. Future: exponential backoff with jitter, retry-after honoring, idempotency-aware. May live in `neton-http-client` or a separate `neton-resilience` module.
- **Migration**: `neton-storage` and `privchat-client` continue using direct Ktor Client. Follow-up to migrate after v0.1 stabilizes.
- **Anthropic prompt cache**: `cache_control` markers and reporting of `cache_read_input_tokens` / `cache_creation_input_tokens` in v0.2.
- **OpenAI Responses API**: v0.1 uses Chat Completions (`/v1/chat/completions`). OpenAI's newer Responses API (`/v1/responses`) for o1+ reasoning may need a separate path in v0.2.
- **Structured output**: `responseFormat = JsonSchema(...)` + `streamObject` API in v0.2.
- **Observability beyond usage recorder**: dedicated metrics SPI (OpenTelemetry / Micrometer-style) in v0.2 or later.
- **DSL re-export of common helpers**: `env(...)` is referenced in DSL examples but lives in `neton-core`; confirm export path during PR1.

---

## 10. References

- Vercel AI SDK Core API: https://sdk.vercel.ai/docs/ai-sdk-core (cross-checked, not copied)
- OpenAI Chat Completions API: https://platform.openai.com/docs/api-reference/chat
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages
- Anthropic SSE event types: https://docs.anthropic.com/en/api/messages-streaming
- OpenRouter API (OpenAI-compatible): https://openrouter.ai/docs
- DashScope OpenAI-compatible mode: https://help.aliyun.com/zh/dashscope/developer-reference/compatibility-of-openai-with-dashscope
- Neton existing `RedisComponent` for component pattern: `neton/neton-redis/src/commonMain/kotlin/neton/redis/RedisComponent.kt`
- Neton config conventions: `neton/neton-core/src/commonMain/kotlin/neton/core/config/ConfigLoader.kt`
- Prior KMP+Ktor Client patterns: `neton/neton-storage/` and `privchat/neton-application-module-privchat/client/`
