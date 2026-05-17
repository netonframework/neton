# PR1: `neton-ai` v0.1 — non-stream `generateText` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Land `neton-ai` non-stream core: `AiClient.generateText { ... }` works against OpenAI-compatible AND Anthropic native APIs, with provider-neutral types, tool-call loop, model routing (explicit + policy + default + fallback-eligible retry), usage recording, and dual-usage architecture (standalone `AiClient.create { ... }` factory + Neton `ai { ... }` component). Stream/embed/Gemini deferred to PR2/PR3.

**Architecture:** New KMP module `neton-ai` (package `neton.ai`) on top of `neton-http-client` (PR0). Single-layer SPI: provider adapters implement `AiTextModel.generate(ProviderCallRequest)` returning `ProviderCallResponse`; `DefaultAiClient` owns routing + tool loop + usage recording. Public API uses provider-neutral types only; `kotlinx.serialization.json.JsonElement` and `io.ktor.*` never leak. Dual-usage: `AiClient.create { httpClient = ...; providers { ... }; routing { ... } }` standalone factory + `AiComponent` thin adapter for `Neton.run { ai { ... } }`.

**Tech Stack:** Kotlin 2.3.10 KMP (macosArm64/X64, linuxX64/Arm64, mingwX64), `neton-http-client` (PR0), `kotlinx-serialization-json` 1.10.0, `kotlinx-coroutines` 1.10.2. Ktor MockEngine for adapter integration tests via `neton-http-client.NetonHttpClient.create` with injected engine factory.

**Spec reference:** `docs/superpowers/specs/2026-05-17-neton-ai-and-http-client-design.md` §3, §4.1, §4.2.
**PR0 dependency:** Commit `08d2fec` (PR0 final cleanup) — `NetonHttpClient.create { ... }` standalone factory exists; `NetonHttpStreamChunk` / SSE parsers exist (unused in PR1 — PR2 will use); `NetonRedactionPolicy.DEFAULT_REDACTED_HEADERS` exists (PR1 Task 19 wires it in for ai logging).

**KtorHttpAdapter.kt unstaged change**: STILL DO NOT TOUCH. Same rule throughout PR1.

---

## Hard Constraints (carried from spec + PR0 review)

1. `neton-ai` public API (any class/fun in `neton.ai` that is NOT `internal`) MUST NOT import:
   - `io.ktor.*` (use `neton.http.client.*` instead)
   - `kotlinx.serialization.json.JsonElement` / `JsonObject` etc.
   - Provider-specific wire DTOs (OpenAI / Anthropic JSON shapes stay in `adapter/<name>/dto/`)
2. `AiClient.create { ... }` standalone factory MUST work without `Neton.run` / `NetonContext` / `LoggerFactory`. Standalone-path files (`AiClient.kt`, `AiConfig.kt`, `AiClientFactory.kt`, internal `DefaultAiClient.kt`, provider adapters) MUST NOT import `neton.core.*` or `neton.logging.*`. Only `AiComponent.kt` is allowed to.
3. `DefaultAiClient` MUST use `NetonHttpClient` (from `neton-http-client`), NEVER `io.ktor.client.HttpClient` directly.
4. Provider adapters MUST construct requests via `NetonHttpRequest` and read responses via `NetonHttpResponse.body` (String). NO `io.ktor.*` types in adapter code.
5. API keys MUST NEVER appear in any log output. Provider implementations MUST NOT log `Authorization` / `X-Api-Key` / `anthropic-api-key` headers in any form. Task 19 wires `NetonRedactionPolicy` into provider debug paths; Task 19 acceptance gate verifies via log capture.
6. PR2 will add `streamText` + `AiStreamingTextModel.stream()`. PR1 creates the empty `AiStreamingTextModel` interface as SPI skeleton (no methods yet) so PR2 can extend without breaking. Same for `AiEmbeddingModel` in PR3.
7. PR1 fallback rule (DefaultModelRouter + DefaultAiClient): when `request.modelPolicy != null`, try `policy.prefer` then `policy.fallback` in order; fallback only on `AiError.isFallbackEligible() == true` (Network/Timeout/RateLimited/ServerError 5xx). When `request.model != null` (explicit), NO fallback. When neither set, use `defaultModel` (no fallback). Round ≥ 2 of tool loop NOT fallback eligible (state already accumulated).

---

## File Structure (new files only; no edits to existing files except `settings.gradle.kts`)

```
neton-ai/
├── build.gradle.kts                                                # Task 1
└── src/
    ├── commonMain/kotlin/neton/ai/
    │   ├── AiClient.kt                                             # Task 10 (interface + Companion.create)
    │   ├── AiComponent.kt                                          # Task 17 (NetonComponent + DSL entry)
    │   ├── AiConfig.kt                                             # Task 15 (DSL config root)
    │   ├── AiContent.kt                                            # Task 2 (sealed; Text only)
    │   ├── AiError.kt                                              # Task 3 (sealed + kind + isFallbackEligible)
    │   ├── AiException.kt                                          # Task 3 (RuntimeException wrapper)
    │   ├── AiFinishReason.kt                                       # Task 2 (enum)
    │   ├── AiMessage.kt                                            # Task 2 (data class)
    │   ├── AiModelId.kt                                            # Task 4 (parse + TDD)
    │   ├── AiRole.kt                                               # Task 2 (enum)
    │   ├── AiToolCall.kt                                           # Task 2
    │   ├── AiToolDefinition.kt                                     # Task 2 (+ AiToolExecutor fun interface)
    │   ├── AiToolResult.kt                                         # Task 2 (+ AiToolResultFormat enum)
    │   ├── AiUsage.kt                                              # Task 2 (all fields nullable)
    │   ├── GenerateTextRequest.kt                                  # Task 5
    │   ├── GenerateTextResult.kt                                   # Task 5
    │   ├── ToolChoice.kt                                           # Task 2 (sealed)
    │   ├── adapter/
    │   │   ├── anthropic/
    │   │   │   ├── AnthropicProvider.kt                            # Task 14
    │   │   │   ├── AnthropicTextModel.kt                           # Task 14
    │   │   │   ├── AnthropicRequestMapper.kt                       # Task 13
    │   │   │   ├── AnthropicResponseMapper.kt                      # Task 14
    │   │   │   └── dto/
    │   │   │       ├── AnthropicWireRequest.kt                     # Task 13
    │   │   │       └── AnthropicWireResponse.kt                    # Task 14
    │   │   └── openaicompatible/
    │   │       ├── OpenAiCompatibleProvider.kt                     # Task 12
    │   │       ├── OpenAiCompatibleTextModel.kt                    # Task 12
    │   │       ├── OpenAiCompatibleRequestMapper.kt                # Task 11
    │   │       ├── OpenAiCompatibleResponseMapper.kt               # Task 12
    │   │       └── dto/
    │   │           ├── OpenAiWireRequest.kt                        # Task 11
    │   │           └── OpenAiWireResponse.kt                       # Task 12
    │   ├── builder/
    │   │   ├── AiToolDefinitionBuilder.kt                          # Task 5
    │   │   └── GenerateTextRequestBuilder.kt                       # Task 5
    │   ├── config/
    │   │   ├── AnthropicSpec.kt                                    # Task 15
    │   │   ├── OpenAiCompatibleSpec.kt                             # Task 15
    │   │   ├── PolicyBuilder.kt                                    # Task 15
    │   │   ├── ProviderSpec.kt                                     # Task 15 (sealed interface)
    │   │   ├── ProvidersBuilder.kt                                 # Task 15
    │   │   ├── RoutingBuilder.kt                                   # Task 15
    │   │   ├── UsageBuilder.kt                                     # Task 15
    │   │   └── UsageConfig.kt                                      # Task 15
    │   ├── internal/
    │   │   ├── AiClientFactory.kt                                  # Task 10 (internal)
    │   │   ├── AiConfigMerge.kt                                    # Task 16 (file ↔ DSL merge + fromMap)
    │   │   ├── DefaultAiClient.kt                                  # Task 10
    │   │   ├── DefaultModelRouter.kt                               # Task 7
    │   │   ├── DefaultProviderRegistry.kt                          # Task 9
    │   │   ├── ToolLoop.kt                                         # Task 9
    │   │   └── UsageAggregator.kt                                  # Task 8
    │   ├── provider/
    │   │   ├── AiEmbeddingModel.kt                                 # Task 6 (empty skeleton; PR3 adds embed())
    │   │   ├── AiProvider.kt                                       # Task 6
    │   │   ├── AiStreamingTextModel.kt                             # Task 6 (empty skeleton; PR2 adds stream())
    │   │   ├── AiTextModel.kt                                      # Task 6
    │   │   ├── ProviderCallRequest.kt                              # Task 6
    │   │   ├── ProviderCallResponse.kt                             # Task 6
    │   │   └── ProviderRegistry.kt                                 # Task 6
    │   ├── routing/
    │   │   ├── ModelPolicy.kt                                      # Task 7
    │   │   ├── ModelRouter.kt                                      # Task 7
    │   │   └── RoutingConfig.kt                                    # Task 7
    │   └── usage/
    │       ├── AiUsageEvent.kt                                     # Task 8
    │       ├── AiUsageRecorder.kt                                  # Task 8
    │       ├── LoggingAiUsageRecorder.kt                           # Task 8
    │       └── NoopAiUsageRecorder.kt                              # Task 8
    └── commonTest/kotlin/neton/ai/
        ├── AiClientFactoryStandaloneTest.kt                        # Task 18 (Mode 1 contract guardrail)
        ├── AiComponentBootTest.kt                                  # Task 18 (Mode 2)
        ├── AiErrorTest.kt                                          # Task 3
        ├── AiModelIdTest.kt                                        # Task 4
        ├── ToolLoopTest.kt                                         # Task 9 (scripted-provider TDD)
        ├── UsageAggregatorTest.kt                                  # Task 8
        ├── adapter/
        │   ├── anthropic/
        │   │   ├── AnthropicIntegrationTest.kt                     # Task 14 (MockEngine end-to-end)
        │   │   ├── AnthropicRequestMapperTest.kt                   # Task 13
        │   │   └── AnthropicResponseMapperTest.kt                  # Task 14
        │   └── openaicompatible/
        │       ├── OpenAiCompatibleIntegrationTest.kt              # Task 12 (MockEngine end-to-end)
        │       ├── OpenAiCompatibleRequestMapperTest.kt            # Task 11
        │       └── OpenAiCompatibleResponseMapperTest.kt           # Task 12
        ├── builder/
        │   └── GenerateTextRequestBuilderTest.kt                   # Task 5
        ├── config/
        │   └── AiConfigMergeTest.kt                                # Task 16
        ├── routing/
        │   └── DefaultModelRouterTest.kt                           # Task 7
        └── usage/
            └── RedactionVerificationTest.kt                        # Task 19 (PR0 Gate 13 close)
```

**Modified files (1):**
- `settings.gradle.kts` — add `include(":neton-ai")`

---

## Task 1: Module skeleton + Gradle deps

**Files:**
- Create: `neton-ai/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create `neton-ai/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        val macosMain by creating { dependsOn(posixMain) }
        val linuxMain by creating { dependsOn(posixMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(project(":neton-http-client"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
```

Notes:
- `neton-http-client` is a project dependency (we built it in PR0).
- `kotlinx.serialization.json` is used **internally only** (DTOs in `adapter/<name>/dto/`, builder typed codec). MUST NOT leak to public API.
- `ktor.client.mock` is in `commonTest` only — used to inject MockEngine when constructing an `NetonHttpClient` for adapter integration tests.
- `neton-core` + `neton-logging` are needed because `AiComponent` uses `NetonComponent` / `NetonContext` / `LoggerFactory`. Standalone path does not use them (enforced by Task 18 grep guardrail).

- [ ] **Step 2: Add `:neton-ai` to `settings.gradle.kts`**

Insert after `:neton-http-client` (alphabetically clean):

```kotlin
include(":neton-http-client")// HTTP Client 基础设施模块（KMP Ktor Client wrapper + SSE + retry primitive）
include(":neton-ai")         // AI 抽象层（generateText/streamText/tool loop/router/usage, OpenAi-compat + Anthropic v0.1）
include(":neton-routing")    // 路由组件模块
```

- [ ] **Step 3: Verify module is recognized**

Run: `./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:tasks -q 2>&1 | head -5`
Expected: prints task list; no "Project ':neton-ai' not found".

- [ ] **Step 4: Commit (only the 2 files)**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/build.gradle.kts settings.gradle.kts
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add neton-ai module skeleton (KMP, depends on neton-http-client)"
```

---

## Task 2: Core data types (group: AiRole / AiContent / AiMessage / AiToolCall / AiToolResult / AiUsage / AiFinishReason / AiToolDefinition / ToolChoice)

Pure data types, no behavior. Grouped per spec §3.2.

**Files (9 files in `src/commonMain/kotlin/neton/ai/`):**
- `AiRole.kt`
- `AiContent.kt`
- `AiMessage.kt`
- `AiToolCall.kt`
- `AiToolResult.kt`
- `AiUsage.kt`
- `AiFinishReason.kt`
- `AiToolDefinition.kt`
- `ToolChoice.kt`

- [ ] **Step 1: Write all 9 files**

`AiRole.kt`:
```kotlin
package neton.ai

enum class AiRole { System, User, Assistant, Tool }
```

`AiContent.kt`:
```kotlin
package neton.ai

/**
 * Message content variant. v0.1 ships only Text.
 * Multimodal variants (ImageUrl / ImageData / Audio) will be added in v0.2 as a
 * non-breaking sealed-interface extension; provider mappers will get a compile-time
 * non-exhaustive `when` warning when they need to be updated.
 */
sealed interface AiContent {
    data class Text(val text: String) : AiContent
}
```

`AiMessage.kt`:
```kotlin
package neton.ai

/**
 * Provider-neutral message. Spec §3.2 option α (separate fields, not content blocks).
 *
 * - role=Assistant with toolCalls → model requesting tools
 * - role=Tool with toolCallId    → tool execution result going back to model
 * - metadata: lightweight provider-specific hints (e.g., providerMessageId); do NOT put secrets here
 */
data class AiMessage(
    val role: AiRole,
    val content: List<AiContent> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

`AiToolCall.kt`:
```kotlin
package neton.ai

/**
 * Model's request to invoke a tool. `argumentsJson` is the raw JSON string produced by the model;
 * caller (or DSL typed codec) parses it.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)
```

`AiToolResult.kt`:
```kotlin
package neton.ai

/**
 * Result of executing a tool. `content` is freeform (JSON or plain text); `format` tells consumers
 * how to interpret it. `isError = true` indicates the executor threw; `content` holds the message.
 */
data class AiToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
    val format: AiToolResultFormat = AiToolResultFormat.Json,
)

enum class AiToolResultFormat { Json, Text }
```

`AiUsage.kt`:
```kotlin
package neton.ai

/**
 * Token usage. ALL fields nullable: providers vary (some give only total, some only input/output,
 * streaming sometimes omits usage). AiClient does NOT auto-derive totalTokens from input+output;
 * downstream aggregation is the recorder's responsibility.
 */
data class AiUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)
```

`AiFinishReason.kt`:
```kotlin
package neton.ai

/**
 * Provider-neutral finish reason. Provider mappers must NOT leak provider-specific strings here.
 * Use `Other` as catch-all.
 *
 * Note: failure does NOT map to a finish reason; failures flow through AiError / AiException.
 */
enum class AiFinishReason {
    Stop,            // natural end
    Length,          // max_tokens hit
    ToolCalls,       // model paused awaiting tool results
    ContentFilter,   // safety filter triggered
    Other,           // unknown / provider-specific
}
```

`AiToolDefinition.kt`:
```kotlin
package neton.ai

/**
 * Tool registered for a single request. `executor` is optional:
 *  - non-null: AiClient runs it locally inside the tool loop
 *  - null: AiClient returns the tool_calls to the caller (caller decides remote execution / approval / etc.)
 *
 * `inputSchemaJson` is the JSON Schema string sent to the model. v0.1 does NOT auto-generate from
 * @Serializable types; caller passes the schema string. (v0.2 may add a schema generator.)
 */
data class AiToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val executor: AiToolExecutor? = null,
)

fun interface AiToolExecutor {
    /** Input is the raw argumentsJson produced by the model; return value becomes AiToolResult.content. */
    suspend fun execute(argumentsJson: String): String
}
```

`ToolChoice.kt`:
```kotlin
package neton.ai

sealed interface ToolChoice {
    data object Auto : ToolChoice                                  // model decides (default)
    data object None : ToolChoice                                  // forbid tool use
    data object Required : ToolChoice                              // must use some tool
    data class Named(val name: String) : ToolChoice                // must use this specific tool
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiRole.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiContent.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiMessage.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiToolCall.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiToolResult.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiUsage.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiFinishReason.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiToolDefinition.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/ToolChoice.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add core data types (AiRole/Content/Message/ToolCall/ToolResult/Usage/FinishReason/ToolDefinition/ToolChoice)"
```

---

## Task 3: `AiError` + `AiException` + `kind` + `isFallbackEligible` (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/AiError.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/AiException.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/AiErrorTest.kt`

- [ ] **Step 1: Write failing tests first**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/AiErrorTest.kt
package neton.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiErrorTest {

    @Test fun kindNetwork() = assertEquals("Network", AiError.Network("x", null).kind)
    @Test fun kindTimeout() = assertEquals("Timeout", AiError.Timeout("x", null).kind)
    @Test fun kindRateLimited() = assertEquals("RateLimited", AiError.RateLimited(null, "x").kind)
    @Test fun kindServerError() = assertEquals("ServerError", AiError.ServerError(503, "x").kind)
    @Test fun kindUnauthorized() = assertEquals("Unauthorized", AiError.Unauthorized("x").kind)
    @Test fun kindForbidden() = assertEquals("Forbidden", AiError.Forbidden("x").kind)
    @Test fun kindInvalidRequest() = assertEquals("InvalidRequest", AiError.InvalidRequest("x").kind)
    @Test fun kindContextLengthExceeded() = assertEquals("ContextLengthExceeded", AiError.ContextLengthExceeded("x").kind)
    @Test fun kindContentFilter() = assertEquals("ContentFilter", AiError.ContentFilter("x").kind)
    @Test fun kindModelNotFound() = assertEquals("ModelNotFound", AiError.ModelNotFound("openai:gpt-x", "x").kind)
    @Test fun kindUnknown() = assertEquals("Unknown", AiError.Unknown("x", null).kind)

    @Test fun fallbackEligibleNetwork() = assertTrue(AiError.Network("x", null).isFallbackEligible())
    @Test fun fallbackEligibleTimeout() = assertTrue(AiError.Timeout("x", null).isFallbackEligible())
    @Test fun fallbackEligibleRateLimited() = assertTrue(AiError.RateLimited(null, "x").isFallbackEligible())
    @Test fun fallbackEligibleServer5xx() = assertTrue(AiError.ServerError(503, "x").isFallbackEligible())
    @Test fun fallbackIneligibleServer4xx() = assertFalse(AiError.ServerError(404, "x").isFallbackEligible())
    @Test fun fallbackIneligibleUnauthorized() = assertFalse(AiError.Unauthorized("x").isFallbackEligible())
    @Test fun fallbackIneligibleForbidden() = assertFalse(AiError.Forbidden("x").isFallbackEligible())
    @Test fun fallbackIneligibleInvalidRequest() = assertFalse(AiError.InvalidRequest("x").isFallbackEligible())
    @Test fun fallbackIneligibleContextLengthExceeded() = assertFalse(AiError.ContextLengthExceeded("x").isFallbackEligible())
    @Test fun fallbackIneligibleContentFilter() = assertFalse(AiError.ContentFilter("x").isFallbackEligible())
    @Test fun fallbackIneligibleModelNotFound() = assertFalse(AiError.ModelNotFound("x", "x").isFallbackEligible())
    @Test fun fallbackIneligibleUnknown() = assertFalse(AiError.Unknown("x", null).isFallbackEligible())

    @Test fun aiExceptionWrapsErrorAndPropagatesMessageAndCause() {
        val cause = RuntimeException("boom")
        val err = AiError.Network("net failed", cause)
        val ex = AiException(err)
        assertEquals("net failed", ex.message)
        assertEquals(cause, ex.cause)
        assertEquals(err, ex.error)
    }
}
```

- [ ] **Step 2: Run tests (expect failure)**

Run: `./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.AiErrorTest" 2>&1 | tail -10`
Expected: FAIL with "Unresolved reference: AiError".

- [ ] **Step 3: Implement `AiError.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiError.kt
package neton.ai

sealed interface AiError {
    val message: String
    val cause: Throwable?

    data class Network(override val message: String, override val cause: Throwable?) : AiError
    data class Timeout(override val message: String, override val cause: Throwable?) : AiError
    data class RateLimited(val retryAfterMillis: Long?, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ServerError(val statusCode: Int, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Unauthorized(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Forbidden(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class InvalidRequest(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ContextLengthExceeded(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ContentFilter(override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class ModelNotFound(val modelId: String, override val message: String) : AiError {
        override val cause: Throwable? = null
    }
    data class Unknown(override val message: String, override val cause: Throwable?) : AiError
}

/** Stable string for telemetry (refactor-safe; do not use class.simpleName). */
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

/**
 * Router fallback eligibility (PR1 rule):
 *  - Network / Timeout / RateLimited / ServerError(5xx) → eligible
 *  - everything else → not eligible (auth, semantic, ModelNotFound — configuration issues; don't paper over)
 */
fun AiError.isFallbackEligible(): Boolean = when (this) {
    is AiError.Network -> true
    is AiError.Timeout -> true
    is AiError.RateLimited -> true
    is AiError.ServerError -> statusCode in 500..599
    else -> false
}
```

- [ ] **Step 4: Implement `AiException.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiException.kt
package neton.ai

/**
 * Carrier exception so AiError (sealed interface, not Throwable) can be thrown across coroutines.
 * AiClient public API throws this; callers `catch (e: AiException) { when (val err = e.error) { ... } }`.
 */
class AiException(val error: AiError) : RuntimeException(error.message, error.cause)
```

- [ ] **Step 5: Run tests (expect pass)**

Run: `./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.AiErrorTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all 24 tests passing.

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiError.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiException.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/AiErrorTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiError sealed taxonomy + kind + isFallbackEligible + AiException"
```

---

## Task 4: `AiModelId` with `parse` (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/AiModelId.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/AiModelIdTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/AiModelIdTest.kt
package neton.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiModelIdTest {

    @Test fun parsesSimpleProviderAndModel() {
        val id = AiModelId.parse("openai:gpt-4o-mini")
        assertEquals(AiModelId("openai", "gpt-4o-mini"), id)
    }

    @Test fun splitsAtFirstColonOnly() {
        // OpenRouter style: "openrouter:anthropic/claude-sonnet-4.5"
        val id = AiModelId.parse("openrouter:anthropic/claude-sonnet-4.5")
        assertEquals("openrouter", id.providerId)
        assertEquals("anthropic/claude-sonnet-4.5", id.modelName)
    }

    @Test fun modelNameMayContainSlashesAndDots() {
        val id = AiModelId.parse("vertex:gemini-1.5-pro/preview")
        assertEquals("vertex", id.providerId)
        assertEquals("gemini-1.5-pro/preview", id.modelName)
    }

    @Test fun modelNameMayContainAdditionalColons() {
        val id = AiModelId.parse("custom:family:v2:beta")
        assertEquals("custom", id.providerId)
        assertEquals("family:v2:beta", id.modelName)
    }

    @Test fun toStringRoundTrip() {
        val id = AiModelId("anthropic", "claude-sonnet-4.5")
        assertEquals("anthropic:claude-sonnet-4.5", id.toString())
        assertEquals(id, AiModelId.parse(id.toString()))
    }

    @Test fun rejectsMissingColon() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("just-a-model") }
    }

    @Test fun rejectsEmptyProvider() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse(":gpt-4o") }
    }

    @Test fun rejectsEmptyModel() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("openai:") }
    }

    @Test fun rejectsBlankString() {
        assertFailsWith<IllegalArgumentException> { AiModelId.parse("") }
    }
}
```

- [ ] **Step 2: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.AiModelIdTest" 2>&1 | tail -10`

Expected: FAIL with "Unresolved reference: AiModelId".

- [ ] **Step 3: Implement `AiModelId.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiModelId.kt
package neton.ai

/**
 * "provider:model" identifier. Split at FIRST ':'; model name may contain '/', ':', '-', '.'.
 *
 * Examples:
 *   openai:gpt-4o-mini
 *   anthropic:claude-sonnet-4.5
 *   openrouter:anthropic/claude-sonnet-4.5
 *   vertex:gemini-1.5-pro/preview
 */
data class AiModelId(val providerId: String, val modelName: String) {
    override fun toString(): String = "$providerId:$modelName"

    companion object {
        fun parse(s: String): AiModelId {
            val idx = s.indexOf(':')
            require(idx > 0 && idx < s.length - 1) {
                "Invalid model id '$s', expected 'provider:model' (non-empty on both sides of first ':')"
            }
            return AiModelId(s.substring(0, idx), s.substring(idx + 1))
        }
    }
}
```

- [ ] **Step 4: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.AiModelIdTest" 2>&1 | tail -10`

Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiModelId.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/AiModelIdTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiModelId with first-colon-split parse + 9 tests"
```

---

## Task 5: `GenerateTextRequest` / `GenerateTextResult` + DSL builders (TDD for builders)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextRequest.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextResult.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/builder/GenerateTextRequestBuilder.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/builder/AiToolDefinitionBuilder.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/builder/GenerateTextRequestBuilderTest.kt`

- [ ] **Step 1: Create `GenerateTextRequest.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextRequest.kt
package neton.ai

/**
 * Non-streaming chat request. See spec §3.4.
 *
 * Routing precedence (DefaultModelRouter):
 *   1. model != null → use exactly this (NO fallback)
 *   2. modelPolicy != null → try policy.prefer + policy.fallback (fallback only on fallback-eligible errors)
 *   3. neither set → use AiConfig.routing.defaultModel (NO fallback)
 *   4. defaultModel also null → throw AiException(InvalidRequest)
 */
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
```

- [ ] **Step 2: Create `GenerateTextResult.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextResult.kt
package neton.ai

/**
 * Final result of a (possibly multi-round) generateText call.
 *
 * - text: concatenation of all Text content in the LAST assistant message
 * - toolCalls / toolResults: ACCUMULATED across all tool-loop rounds
 * - usage: aggregated across rounds (null-safe sum, null if all rounds had null usage)
 * - providerId / modelName: actual (post-fallback) provider+model that produced the result
 * - rounds: how many model.generate() calls happened (>= 1)
 */
data class GenerateTextResult(
    val text: String,
    val message: AiMessage,
    val toolCalls: List<AiToolCall>,
    val toolResults: List<AiToolResult>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
    val providerId: String,
    val modelName: String,
    val rounds: Int,
)
```

- [ ] **Step 3: Write failing builder tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/builder/GenerateTextRequestBuilderTest.kt
package neton.ai.builder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.ToolChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateTextRequestBuilderTest {

    @Test fun systemUserAssistantHelpersCreateExpectedMessages() {
        val req = GenerateTextRequestBuilder().apply {
            system("You are helpful.")
            user("Hello")
            assistant("Hi there!")
        }.build()
        assertEquals(3, req.messages.size)
        assertEquals(AiRole.System, req.messages[0].role)
        assertEquals(AiContent.Text("You are helpful."), req.messages[0].content.single())
        assertEquals(AiRole.User, req.messages[1].role)
        assertEquals(AiRole.Assistant, req.messages[2].role)
    }

    @Test fun toolResultHelperFillsToolCallId() {
        val req = GenerateTextRequestBuilder().apply {
            user("call tool")
            toolResult("call_42", """{"ok":true}""")
        }.build()
        val toolMsg = req.messages.last()
        assertEquals(AiRole.Tool, toolMsg.role)
        assertEquals("call_42", toolMsg.toolCallId)
        assertEquals("""{"ok":true}""", (toolMsg.content.single() as AiContent.Text).text)
    }

    @Test fun modelStringParsedToAiModelId() {
        val req = GenerateTextRequestBuilder().apply {
            model = "openai:gpt-4o-mini"
            user("hi")
        }.build()
        assertEquals(AiModelId("openai", "gpt-4o-mini"), req.model)
    }

    @Test fun nullModelStringYieldsNullModel() {
        val req = GenerateTextRequestBuilder().apply {
            modelPolicy = "strong"
            user("hi")
        }.build()
        assertNull(req.model)
        assertEquals("strong", req.modelPolicy)
    }

    @Test fun toolBlockRegistersToolDefinition() {
        val req = GenerateTextRequestBuilder().apply {
            user("compute")
            tool("get_balance") {
                description = "Get user balance"
                inputSchemaJson = """{"type":"object"}"""
                execute { _ -> """{"balance":100}""" }
            }
        }.build()
        assertEquals(1, req.tools.size)
        val def = req.tools.single()
        assertEquals("get_balance", def.name)
        assertEquals("Get user balance", def.description)
        assertEquals("""{"type":"object"}""", def.inputSchemaJson)
        assertTrue(def.executor != null, "executor should be set")
    }

    @Test fun typedToolExecutorDecodesAndEncodesViaJson() = kotlinx.coroutines.test.runTest {
        val req = GenerateTextRequestBuilder().apply {
            user("compute")
            tool<BalanceIn, BalanceOut>("get_balance") {
                description = "Get balance"
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}"""
                execute { input -> BalanceOut(balance = input.userId * 10) }
            }
        }.build()
        val def = req.tools.single()
        val result = def.executor!!.execute("""{"userId":7}""")
        // Output is JSON-encoded BalanceOut
        val parsed = Json.decodeFromString<BalanceOut>(result)
        assertEquals(70, parsed.balance)
    }

    @Test fun toolChoiceDefaultsToAuto() {
        val req = GenerateTextRequestBuilder().apply { user("hi") }.build()
        assertTrue(req.toolChoice is ToolChoice.Auto)
    }

    @Test fun defaultMaxToolRoundsIs3() {
        val req = GenerateTextRequestBuilder().apply { user("hi") }.build()
        assertEquals(3, req.maxToolRounds)
    }

    @Serializable data class BalanceIn(val userId: Long)
    @Serializable data class BalanceOut(val balance: Long)
}
```

- [ ] **Step 4: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.builder.GenerateTextRequestBuilderTest" 2>&1 | tail -10`

Expected: FAIL with unresolved references.

- [ ] **Step 5: Implement `AiToolDefinitionBuilder.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/builder/AiToolDefinitionBuilder.kt
package neton.ai.builder

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor

/**
 * Raw tool builder. Caller supplies `inputSchemaJson` (we do NOT auto-generate from @Serializable
 * in v0.1) and either a raw-JSON executor or a typed one (see `tool<TIn, TOut>` extension).
 *
 * Use within GenerateTextRequestBuilder.tool { ... }.
 */
class AiToolDefinitionBuilder internal constructor(private val name: String) {
    var description: String = ""
    var inputSchemaJson: String = "{}"
    internal var executor: AiToolExecutor? = null

    /** Raw executor: receive the model's JSON argument string, return the JSON (or text) result string. */
    fun execute(handler: suspend (argumentsJson: String) -> String) {
        executor = AiToolExecutor { handler(it) }
    }

    internal fun build(): AiToolDefinition = AiToolDefinition(
        name = name,
        description = description,
        inputSchemaJson = inputSchemaJson,
        executor = executor,
    )

    /** Internal JSON used by typed-codec helper. Lazily configured to ignore unknown keys. */
    internal companion object {
        val internalJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * Typed-codec convenience for tools whose input and output are @Serializable. The DSL uses
 * kotlinx.serialization INTERNALLY only; the resulting AiToolDefinition still exposes plain
 * String arguments/results to AiClient (no JsonElement on public API).
 *
 * NOTE: caller still passes `inputSchemaJson` explicitly. v0.1 does NOT auto-generate from TIn.
 */
inline fun <reified TIn, reified TOut> AiToolDefinitionBuilder.execute(
    crossinline handler: suspend (TIn) -> TOut,
) {
    val inSerializer = serializer<TIn>()
    val outSerializer = serializer<TOut>()
    execute { argsJson ->
        val input = AiToolDefinitionBuilder.internalJson.decodeFromString(inSerializer, argsJson)
        val output = handler(input)
        AiToolDefinitionBuilder.internalJson.encodeToString(outSerializer, output)
    }
}
```

- [ ] **Step 6: Implement `GenerateTextRequestBuilder.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/builder/GenerateTextRequestBuilder.kt
package neton.ai.builder

import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiToolDefinition
import neton.ai.GenerateTextRequest
import neton.ai.ToolChoice

/**
 * DSL builder for non-streaming generateText. See spec §3.4.
 *
 * Usage:
 *   ai.generateText {
 *       modelPolicy = "strong"
 *       system("You are helpful.")
 *       user("Hello")
 *       tool("get_balance") { ... }
 *   }
 */
class GenerateTextRequestBuilder {
    var model: String? = null
    var modelPolicy: String? = null
    var temperature: Double? = null
    var maxTokens: Int? = null
    var topP: Double? = null
    var maxToolRounds: Int = 3
    var toolChoice: ToolChoice = ToolChoice.Auto
    var stopSequences: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()

    private val messages = mutableListOf<AiMessage>()
    private val tools = mutableListOf<AiToolDefinition>()

    fun system(text: String) { messages += AiMessage(AiRole.System, listOf(AiContent.Text(text))) }
    fun user(text: String) { messages += AiMessage(AiRole.User, listOf(AiContent.Text(text))) }
    fun assistant(text: String) { messages += AiMessage(AiRole.Assistant, listOf(AiContent.Text(text))) }
    fun toolResult(callId: String, content: String) {
        messages += AiMessage(
            role = AiRole.Tool,
            content = listOf(AiContent.Text(content)),
            toolCallId = callId,
        )
    }
    fun message(m: AiMessage) { messages += m }
    fun messages(ms: Iterable<AiMessage>) { messages.addAll(ms) }

    fun tool(name: String, block: AiToolDefinitionBuilder.() -> Unit) {
        tools += AiToolDefinitionBuilder(name).apply(block).build()
    }

    internal fun build(): GenerateTextRequest = GenerateTextRequest(
        model = model?.let(AiModelId::parse),
        modelPolicy = modelPolicy,
        messages = messages.toList(),
        tools = tools.toList(),
        toolChoice = toolChoice,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        stopSequences = stopSequences,
        maxToolRounds = maxToolRounds,
        metadata = metadata,
    )
}

/** Typed-tool helper. See AiToolDefinitionBuilder for the typed `execute<TIn, TOut> { ... }` extension. */
inline fun <reified TIn, reified TOut> GenerateTextRequestBuilder.tool(
    name: String,
    block: AiToolDefinitionBuilder.() -> Unit,
) = tool(name, block)  // signature exists for type inference; body delegates to the raw form
```

- [ ] **Step 7: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.builder.GenerateTextRequestBuilderTest" 2>&1 | tail -10`

Expected: 8 tests pass.

- [ ] **Step 8: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextRequest.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/GenerateTextResult.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/builder/GenerateTextRequestBuilder.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/builder/AiToolDefinitionBuilder.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/builder/GenerateTextRequestBuilderTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add GenerateTextRequest/Result + DSL builders (typed-tool codec via kotlinx.serialization internally)"
```

---

## Task 6: SPI types (provider interfaces + ProviderCallRequest/Response + ProviderRegistry)

Empty `AiStreamingTextModel` / `AiEmbeddingModel` skeletons reserved for PR2/PR3 extension (no methods yet — so adding methods later is binary breaking but acceptable; SPI is consumed only inside `neton-ai`).

**Files (7 files in `src/commonMain/kotlin/neton/ai/provider/`):**

- [ ] **Step 1: Create `ProviderCallRequest.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/ProviderCallRequest.kt
package neton.ai.provider

import neton.ai.AiMessage
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice

/**
 * SPI request. Slim version of GenerateTextRequest with AiClient-level concerns stripped
 * (model / modelPolicy / maxToolRounds). Provider operates on its bound model only.
 */
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
```

- [ ] **Step 2: Create `ProviderCallResponse.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/ProviderCallResponse.kt
package neton.ai.provider

import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiToolCall
import neton.ai.AiUsage

/** One round of model interaction. If toolCalls non-empty, model paused awaiting tool results. */
data class ProviderCallResponse(
    val message: AiMessage,
    val text: String,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage?,
    val finishReason: AiFinishReason,
)
```

- [ ] **Step 3: Create `AiTextModel.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/AiTextModel.kt
package neton.ai.provider

interface AiTextModel {
    val providerId: String
    val modelName: String
    suspend fun generate(request: ProviderCallRequest): ProviderCallResponse
}
```

- [ ] **Step 4: Create `AiStreamingTextModel.kt` (empty skeleton; PR2 adds stream())**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/AiStreamingTextModel.kt
package neton.ai.provider

/**
 * Streaming capability marker. PR2 will add:
 *   fun stream(request: ProviderCallRequest): Flow<AiStreamEvent>
 *
 * PR1 has no implementations; AiClient.streamText is also PR2.
 */
interface AiStreamingTextModel : AiTextModel
```

- [ ] **Step 5: Create `AiEmbeddingModel.kt` (empty skeleton; PR3 adds embed())**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/AiEmbeddingModel.kt
package neton.ai.provider

/**
 * Embedding capability marker. PR3 will add:
 *   suspend fun embed(request: ProviderEmbedRequest): ProviderEmbedResponse
 *
 * PR1 has no implementations; AiClient.embed is also PR3.
 */
interface AiEmbeddingModel {
    val providerId: String
    val modelName: String
}
```

- [ ] **Step 6: Create `AiProvider.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/AiProvider.kt
package neton.ai.provider

/**
 * Provider entry point. Each capability is `null` if the provider/model doesn't support it.
 *
 * PR1: OpenAi-compat + Anthropic both implement textModel(); streamingTextModel() returns null
 * (PR2 will return the same instance as text model when streaming supported); embeddingModel()
 * returns null for now (PR3 wires OpenAi-compat embeddings).
 */
interface AiProvider {
    val id: String
    fun textModel(modelName: String): AiTextModel?
    fun streamingTextModel(modelName: String): AiStreamingTextModel?  // PR1: always null
    fun embeddingModel(modelName: String): AiEmbeddingModel?           // PR1: always null
}
```

- [ ] **Step 7: Create `ProviderRegistry.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/provider/ProviderRegistry.kt
package neton.ai.provider

interface ProviderRegistry {
    fun get(providerId: String): AiProvider?
    fun all(): Map<String, AiProvider>
}
```

- [ ] **Step 8: Verify compilation**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/provider/
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add SPI (AiProvider/AiTextModel/AiStreamingTextModel/AiEmbeddingModel/ProviderCallRequest/Response/ProviderRegistry)"
```

---

## Task 7: Routing types + `DefaultModelRouter` (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/routing/ModelPolicy.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/routing/RoutingConfig.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/routing/ModelRouter.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultModelRouter.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/routing/DefaultModelRouterTest.kt`

- [ ] **Step 1: Pure data types — ModelPolicy / RoutingConfig / ModelRouter**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/routing/ModelPolicy.kt
package neton.ai.routing

import neton.ai.AiModelId

data class ModelPolicy(
    val prefer: List<AiModelId>,
    val fallback: List<AiModelId>,
)
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/routing/RoutingConfig.kt
package neton.ai.routing

import neton.ai.AiModelId

data class RoutingConfig(
    val defaultModel: AiModelId? = null,
    val policies: Map<String, ModelPolicy> = emptyMap(),
)
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/routing/ModelRouter.kt
package neton.ai.routing

import neton.ai.AiModelId

interface ModelRouter {
    /**
     * Resolution priority:
     *  1. explicitModel != null → [explicitModel] (NO fallback)
     *  2. modelPolicy != null → policy.prefer + policy.fallback (in order, fallback only on fallback-eligible errors)
     *  3. defaultModel != null → [defaultModel] (NO fallback)
     *  4. none → throws AiException(InvalidRequest)
     *
     * Callers needing fallback MUST use modelPolicy. Explicit model means EXACTLY this model.
     */
    fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId>
}
```

- [ ] **Step 2: Write failing tests for DefaultModelRouter**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/routing/DefaultModelRouterTest.kt
package neton.ai.routing

import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiModelId
import neton.ai.internal.DefaultModelRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultModelRouterTest {

    private fun router(default: String? = null, policies: Map<String, ModelPolicy> = emptyMap()) =
        DefaultModelRouter(RoutingConfig(
            defaultModel = default?.let(AiModelId::parse),
            policies = policies,
        ))

    @Test fun explicitModelWinsAndIsSingleCandidate() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(AiModelId.parse("anthropic:claude-sonnet-4.5"), null)
        assertEquals(listOf(AiModelId("anthropic", "claude-sonnet-4.5")), candidates)
    }

    @Test fun explicitModelDoesNotFallBackToDefault() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(AiModelId.parse("custom:weird"), null)
        // Even if "custom:weird" doesn't exist, router returns it anyway; ModelNotFound surfaces at provider lookup time
        assertEquals(1, candidates.size)
        assertEquals(AiModelId("custom", "weird"), candidates[0])
    }

    @Test fun policyPreferThenFallbackInOrder() {
        val r = router(policies = mapOf("strong" to ModelPolicy(
            prefer = listOf(AiModelId.parse("anthropic:claude-sonnet-4.5")),
            fallback = listOf(AiModelId.parse("openai:gpt-4o"), AiModelId.parse("deepseek:deepseek-chat")),
        )))
        val candidates = r.resolve(null, "strong")
        assertEquals(listOf(
            AiModelId("anthropic", "claude-sonnet-4.5"),
            AiModelId("openai", "gpt-4o"),
            AiModelId("deepseek", "deepseek-chat"),
        ), candidates)
    }

    @Test fun defaultModelUsedWhenNeitherExplicitNorPolicy() {
        val r = router(default = "openai:gpt-4o-mini")
        val candidates = r.resolve(null, null)
        assertEquals(listOf(AiModelId("openai", "gpt-4o-mini")), candidates)
    }

    @Test fun missingConfigThrowsInvalidRequest() {
        val r = router()
        val ex = assertFailsWith<AiException> { r.resolve(null, null) }
        assertTrue(ex.error is AiError.InvalidRequest, "got ${ex.error::class.simpleName}")
    }

    @Test fun unknownPolicyThrowsInvalidRequest() {
        val r = router(default = "openai:gpt-4o-mini")
        val ex = assertFailsWith<AiException> { r.resolve(null, "nope") }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("nope" in ex.error.message)
    }

    @Test fun emptyPolicyPreferThrowsInvalidRequest() {
        // Validation guarantees this won't happen at AiConfig.validate, but router defends anyway
        val r = router(policies = mapOf("empty" to ModelPolicy(prefer = emptyList(), fallback = emptyList())))
        val ex = assertFailsWith<AiException> { r.resolve(null, "empty") }
        assertTrue(ex.error is AiError.InvalidRequest)
    }
}
```

- [ ] **Step 3: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.routing.DefaultModelRouterTest" 2>&1 | tail -10`

Expected: FAIL with unresolved reference.

- [ ] **Step 4: Implement `DefaultModelRouter.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultModelRouter.kt
package neton.ai.internal

import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiModelId
import neton.ai.routing.ModelRouter
import neton.ai.routing.RoutingConfig

internal class DefaultModelRouter(private val config: RoutingConfig) : ModelRouter {
    override fun resolve(explicitModel: AiModelId?, modelPolicy: String?): List<AiModelId> {
        if (explicitModel != null) return listOf(explicitModel)
        if (modelPolicy != null) {
            val policy = config.policies[modelPolicy]
                ?: throw AiException(AiError.InvalidRequest("Unknown model policy '$modelPolicy'"))
            val candidates = policy.prefer + policy.fallback
            if (candidates.isEmpty()) {
                throw AiException(AiError.InvalidRequest("Policy '$modelPolicy' has no models (prefer + fallback both empty)"))
            }
            return candidates
        }
        val def = config.defaultModel
            ?: throw AiException(AiError.InvalidRequest(
                "No model or modelPolicy specified, and no defaultModel configured"))
        return listOf(def)
    }
}
```

- [ ] **Step 5: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.routing.DefaultModelRouterTest" 2>&1 | tail -10`

Expected: 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/routing/ \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultModelRouter.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/routing/DefaultModelRouterTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add routing types + DefaultModelRouter with 7 tests (explicit > policy > default; no fallback for explicit/default)"
```

---

## Task 8: Usage types + `LoggingAiUsageRecorder` + `UsageAggregator` (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/usage/AiUsageEvent.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/usage/AiUsageRecorder.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/usage/NoopAiUsageRecorder.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/usage/LoggingAiUsageRecorder.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/UsageAggregator.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/UsageAggregatorTest.kt`

- [ ] **Step 1: Usage data + recorder interface + Noop impl**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/usage/AiUsageEvent.kt
package neton.ai.usage

import neton.ai.AiFinishReason
import neton.ai.AiUsage

/**
 * Single tool-loop round usage record. Emitted by DefaultAiClient at each round
 * (success or fallback-eligible failure that's about to fall back).
 *
 * - requestMetadata is caller-sanitized; the built-in LoggingAiUsageRecorder additionally
 *   allowlist-filters to known-safe keys (requestId/traceId/userId/businessTag/channelId).
 * - errorKind uses AiError.kind (stable string), null on success.
 */
data class AiUsageEvent(
    val requestId: String?,
    val providerId: String,
    val modelName: String,
    val usage: AiUsage?,
    val round: Int,
    val requestMetadata: Map<String, String>,
    val timestampEpochMillis: Long,
    val durationMillis: Long,
    val finishReason: AiFinishReason?,
    val success: Boolean,
    val errorKind: String? = null,
)
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/usage/AiUsageRecorder.kt
package neton.ai.usage

interface AiUsageRecorder {
    suspend fun record(event: AiUsageEvent)
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/usage/NoopAiUsageRecorder.kt
package neton.ai.usage

object NoopAiUsageRecorder : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {}
}
```

- [ ] **Step 2: `LoggingAiUsageRecorder` with allowlist**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/usage/LoggingAiUsageRecorder.kt
package neton.ai.usage

/**
 * Built-in convenience recorder. Logs only allowlisted requestMetadata keys.
 *
 * Logger interface is intentionally NOT typed to `neton.logging.Logger` here — that would couple
 * usage/* to neton-logging and break standalone usage. Caller passes a simple lambda or facade.
 *
 * NOTE: callers MUST NOT put prompt text, secrets, or PII in event.requestMetadata; this recorder
 * only filters by key allowlist, not by value content.
 */
class LoggingAiUsageRecorder(
    val emit: (line: String) -> Unit,
    val metadataAllowlist: Set<String> = DEFAULT_METADATA_ALLOWLIST,
) : AiUsageRecorder {
    override suspend fun record(event: AiUsageEvent) {
        val safe = event.requestMetadata.filterKeys { it in metadataAllowlist }
        val sb = StringBuilder("ai.usage")
        sb.append(" provider=").append(event.providerId)
        sb.append(" model=").append(event.modelName)
        sb.append(" round=").append(event.round)
        sb.append(" success=").append(event.success)
        event.errorKind?.let { sb.append(" errorKind=").append(it) }
        event.finishReason?.let { sb.append(" finish=").append(it.name) }
        event.usage?.inputTokens?.let { sb.append(" in=").append(it) }
        event.usage?.outputTokens?.let { sb.append(" out=").append(it) }
        event.usage?.totalTokens?.let { sb.append(" total=").append(it) }
        sb.append(" durMs=").append(event.durationMillis)
        event.requestId?.let { sb.append(" requestId=").append(it) }
        for ((k, v) in safe) sb.append(' ').append(k).append('=').append(v)
        emit(sb.toString())
    }

    companion object {
        val DEFAULT_METADATA_ALLOWLIST: Set<String> = setOf(
            "requestId", "traceId", "userId", "businessTag", "channelId",
        )
    }
}
```

- [ ] **Step 3: Write failing UsageAggregator tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/UsageAggregatorTest.kt
package neton.ai

import neton.ai.internal.aggregateUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageAggregatorTest {

    @Test fun emptyListReturnsNull() {
        assertNull(aggregateUsage(emptyList()))
    }

    @Test fun allNullEntriesReturnsNull() {
        assertNull(aggregateUsage(listOf(null, null, null)))
    }

    @Test fun singleUsageReturnsSelf() {
        val u = AiUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30)
        assertEquals(u, aggregateUsage(listOf(u)))
    }

    @Test fun sumsNonNullFieldsAcrossRounds() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val u2 = AiUsage(inputTokens = 7, outputTokens = 3, totalTokens = 10)
        val u3 = AiUsage(inputTokens = 2, outputTokens = 1, totalTokens = 3)
        assertEquals(
            AiUsage(inputTokens = 19, outputTokens = 9, totalTokens = 28),
            aggregateUsage(listOf(u1, u2, u3)),
        )
    }

    @Test fun preservesNullPerFieldWhenAllNullForThatField() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = null, totalTokens = null)
        val u2 = AiUsage(inputTokens = 5, outputTokens = null, totalTokens = null)
        assertEquals(
            AiUsage(inputTokens = 15, outputTokens = null, totalTokens = null),
            aggregateUsage(listOf(u1, u2)),
        )
    }

    @Test fun mixedNullAndPresentSumsOnlyPresent() {
        val u1 = AiUsage(inputTokens = 10, outputTokens = 5, totalTokens = null)
        val u2 = AiUsage(inputTokens = null, outputTokens = 3, totalTokens = 8)
        // input: 10 (one provided), output: 5+3=8, total: 8 (one provided)
        assertEquals(
            AiUsage(inputTokens = 10, outputTokens = 8, totalTokens = 8),
            aggregateUsage(listOf(u1, u2)),
        )
    }

    @Test fun skipsNullEntries() {
        val u = AiUsage(inputTokens = 4, outputTokens = 2, totalTokens = 6)
        assertEquals(u, aggregateUsage(listOf(null, u, null)))
    }
}
```

- [ ] **Step 4: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.UsageAggregatorTest" 2>&1 | tail -10`

Expected: FAIL.

- [ ] **Step 5: Implement `UsageAggregator.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/UsageAggregator.kt
package neton.ai.internal

import neton.ai.AiUsage

/**
 * Aggregate per-round usage into a single AiUsage.
 *
 * - Empty list OR all-null entries → null
 * - Per field: sum of non-null values across non-null entries, or null if no entry provided it
 */
internal fun aggregateUsage(rounds: List<AiUsage?>): AiUsage? {
    val present = rounds.filterNotNull()
    if (present.isEmpty()) return null
    fun sumOrNull(selector: (AiUsage) -> Int?): Int? {
        val vals = present.mapNotNull(selector)
        return if (vals.isEmpty()) null else vals.sum()
    }
    return AiUsage(
        inputTokens = sumOrNull { it.inputTokens },
        outputTokens = sumOrNull { it.outputTokens },
        totalTokens = sumOrNull { it.totalTokens },
    )
}
```

- [ ] **Step 6: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.UsageAggregatorTest" 2>&1 | tail -10`

Expected: 7 tests pass.

- [ ] **Step 7: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/usage/ \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/UsageAggregator.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/UsageAggregatorTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add usage primitives (Event/Recorder/Noop/Logging) + UsageAggregator with 7 tests"
```

---

## Task 9: `DefaultProviderRegistry` + `ToolLoop` non-stream (TDD via scripted mock provider)

This is the **core algorithm** of PR1. Strict TDD via scripted in-memory provider to cover every branch of spec §3.7 non-streaming tool loop.

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultProviderRegistry.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/ToolLoop.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/ToolLoopTest.kt`

- [ ] **Step 1: Write `DefaultProviderRegistry.kt` (trivial impl)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultProviderRegistry.kt
package neton.ai.internal

import neton.ai.provider.AiProvider
import neton.ai.provider.ProviderRegistry

internal class DefaultProviderRegistry(
    private val providers: Map<String, AiProvider>,
) : ProviderRegistry {
    override fun get(providerId: String): AiProvider? = providers[providerId]
    override fun all(): Map<String, AiProvider> = providers
}
```

- [ ] **Step 2: Write failing ToolLoop tests**

The test fixture defines a scripted provider that returns pre-configured responses in order; this lets us cover every branch without real HTTP.

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/ToolLoopTest.kt
package neton.ai

import kotlinx.coroutines.test.runTest
import neton.ai.internal.DefaultModelRouter
import neton.ai.internal.DefaultProviderRegistry
import neton.ai.internal.runToolLoop
import neton.ai.provider.AiProvider
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.routing.RoutingConfig
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolLoopTest {

    // ---- Test fixtures ----

    private class ScriptedTextModel(
        override val providerId: String,
        override val modelName: String,
        private val script: List<ScriptStep>,
    ) : AiTextModel {
        private var idx = 0
        var calls = 0; private set
        var lastRequest: ProviderCallRequest? = null; private set
        override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
            calls++
            lastRequest = request
            val step = script[idx++]
            return step.invoke()
        }
    }

    private sealed interface ScriptStep {
        fun invoke(): ProviderCallResponse
    }

    private data class Reply(val response: ProviderCallResponse) : ScriptStep {
        override fun invoke() = response
    }

    private data class Fail(val error: AiError) : ScriptStep {
        override fun invoke(): Nothing = throw AiException(error)
    }

    private class ScriptedProvider(
        override val id: String,
        private val model: ScriptedTextModel,
    ) : AiProvider {
        override fun textModel(modelName: String): AiTextModel? =
            if (modelName == model.modelName) model else null
        override fun streamingTextModel(modelName: String) = null
        override fun embeddingModel(modelName: String) = null
    }

    private class CapturingRecorder : AiUsageRecorder {
        val events = mutableListOf<AiUsageEvent>()
        override suspend fun record(event: AiUsageEvent) { events += event }
    }

    private fun textReply(text: String, calls: List<AiToolCall> = emptyList(),
                          usage: AiUsage? = AiUsage(10, 5, 15),
                          finish: AiFinishReason = AiFinishReason.Stop) = Reply(
        ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (calls.isEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = calls,
            ),
            text = if (calls.isEmpty()) text else "",
            toolCalls = calls,
            usage = usage,
            finishReason = if (calls.isEmpty()) finish else AiFinishReason.ToolCalls,
        )
    )

    private fun runLoop(
        request: GenerateTextRequest,
        providerId: String = "p1",
        modelName: String = "m1",
        script: List<ScriptStep>,
        recorder: AiUsageRecorder = CapturingRecorder(),
        extraProviders: Map<String, AiProvider> = emptyMap(),
        defaultModel: String? = "$providerId:$modelName",
    ): GenerateTextResult {
        val model = ScriptedTextModel(providerId, modelName, script)
        val providers = mapOf(providerId to ScriptedProvider(providerId, model)) + extraProviders
        return kotlinx.coroutines.runBlocking {
            runToolLoop(
                request = request,
                registry = DefaultProviderRegistry(providers),
                router = DefaultModelRouter(RoutingConfig(defaultModel = defaultModel?.let(AiModelId::parse))),
                recorder = recorder,
            )
        }
    }

    // ---- Tests ----

    @Test fun singleRoundNoToolCalls() = runTest {
        val res = runLoop(
            request = GenerateTextRequest(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi"))))),
            script = listOf(textReply("Hello world")),
        )
        assertEquals("Hello world", res.text)
        assertEquals(AiFinishReason.Stop, res.finishReason)
        assertEquals(1, res.rounds)
        assertTrue(res.toolCalls.isEmpty())
        assertTrue(res.toolResults.isEmpty())
        assertEquals(AiUsage(10, 5, 15), res.usage)
    }

    @Test fun toolLoopExecutesLocalToolAndContinues() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("balance?")))),
            tools = listOf(AiToolDefinition(
                name = "get_balance",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> """{"balance":42}""" },
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}"""))),
                textReply("You have 42."),
            ),
        )
        assertEquals("You have 42.", res.text)
        assertEquals(2, res.rounds)
        assertEquals(1, res.toolCalls.size)
        assertEquals(1, res.toolResults.size)
        assertEquals("""{"balance":42}""", res.toolResults[0].content)
        assertEquals(false, res.toolResults[0].isError)
        // Usage aggregated across 2 rounds
        assertEquals(AiUsage(20, 10, 30), res.usage)
    }

    @Test fun toolExecutorErrorMarksResultAsError() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "bad_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> error("boom") },
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "bad_tool", "{}"))),
                textReply("recovered"),
            ),
        )
        assertEquals("recovered", res.text)
        assertEquals(true, res.toolResults.single().isError)
        assertTrue("boom" in res.toolResults.single().content)
    }

    @Test fun toolCallWithoutExecutorReturnsEarly() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("call")))),
            tools = listOf(AiToolDefinition(
                name = "remote_tool",
                description = "",
                inputSchemaJson = "{}",
                executor = null,  // no local executor
            )),
        )
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "remote_tool", "{}"))),
            ),
        )
        assertEquals(AiFinishReason.ToolCalls, res.finishReason)
        assertEquals(1, res.rounds)
        assertEquals(1, res.toolCalls.size)
        assertTrue(res.toolResults.isEmpty(), "no local execution happened")
    }

    @Test fun maxToolRoundsExhaustedReturnsToolCallsFinish() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("loop")))),
            maxToolRounds = 2,
            tools = listOf(AiToolDefinition(
                name = "ping", description = "", inputSchemaJson = "{}",
                executor = AiToolExecutor { _ -> "pong" },
            )),
        )
        // Model keeps calling tool forever
        val res = runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "ping", "{}"))),
                textReply("", calls = listOf(AiToolCall("c2", "ping", "{}"))),
            ),
        )
        assertEquals(AiFinishReason.ToolCalls, res.finishReason)
        assertEquals(2, res.rounds)
        assertEquals(2, res.toolCalls.size)
        assertEquals(2, res.toolResults.size)
    }

    @Test fun round1FallbackEligibleErrorTriesNextCandidate() = runTest {
        // Two providers; first throws RateLimited at round 1, second succeeds
        val r1Model = ScriptedTextModel("p1", "m1", listOf(Fail(AiError.RateLimited(null, "429"))))
        val r2Model = ScriptedTextModel("p2", "m2", listOf(textReply("recovered").let { (it as Reply).response }.let { Reply(it) }))
        val providers = mapOf(
            "p1" to ScriptedProvider("p1", r1Model),
            "p2" to ScriptedProvider("p2", r2Model),
        )
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("any" to neton.ai.routing.ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))
        val recorder = CapturingRecorder()
        val res = kotlinx.coroutines.runBlocking {
            neton.ai.internal.runToolLoop(
                request = GenerateTextRequest(
                    modelPolicy = "any",
                    messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                ),
                registry = DefaultProviderRegistry(providers),
                router = router,
                recorder = recorder,
            )
        }
        assertEquals("p2", res.providerId)
        assertEquals("m2", res.modelName)
        assertEquals("recovered", res.text)
        // Recorder should have seen failure record for p1 then success for p2
        assertEquals(2, recorder.events.size)
        assertEquals(false, recorder.events[0].success)
        assertEquals("RateLimited", recorder.events[0].errorKind)
        assertEquals(true, recorder.events[1].success)
    }

    @Test fun round2FailureNotFallbackEligibleEvenIfEligibleError() = runTest {
        // First model: round 1 returns tool_call, round 2 throws fallback-eligible error
        // Even though Network is fallback-eligible, round >= 2 must not fall back (state accumulated)
        val r1Model = ScriptedTextModel("p1", "m1", listOf(
            Reply(ProviderCallResponse(
                message = AiMessage(AiRole.Assistant, emptyList(), listOf(AiToolCall("c1", "t", "{}"))),
                text = "",
                toolCalls = listOf(AiToolCall("c1", "t", "{}")),
                usage = null, finishReason = AiFinishReason.ToolCalls,
            )),
            Fail(AiError.Network("boom", null)),
        ))
        val r2Model = ScriptedTextModel("p2", "m2", listOf(textReply("would-have-recovered").let { (it as Reply).response }.let { Reply(it) }))
        val providers = mapOf(
            "p1" to ScriptedProvider("p1", r1Model),
            "p2" to ScriptedProvider("p2", r2Model),
        )
        val router = DefaultModelRouter(RoutingConfig(
            policies = mapOf("x" to neton.ai.routing.ModelPolicy(
                prefer = listOf(AiModelId("p1", "m1")),
                fallback = listOf(AiModelId("p2", "m2")),
            ))
        ))
        val req = GenerateTextRequest(
            modelPolicy = "x",
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            tools = listOf(AiToolDefinition("t", "", "{}", AiToolExecutor { _ -> "ok" })),
        )
        val ex = assertFailsWith<AiException> {
            kotlinx.coroutines.runBlocking {
                neton.ai.internal.runToolLoop(req, DefaultProviderRegistry(providers), router, CapturingRecorder())
            }
        }
        assertTrue(ex.error is AiError.Network)
        assertEquals(0, r2Model.calls, "fallback should NOT have been attempted after round 2 failure")
    }

    @Test fun nonFallbackEligibleErrorPropagatesImmediately() = runTest {
        val ex = assertFailsWith<AiException> {
            runLoop(
                request = GenerateTextRequest(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x"))))),
                script = listOf(Fail(AiError.Unauthorized("bad key"))),
            )
        }
        assertTrue(ex.error is AiError.Unauthorized)
    }

    @Test fun modelNotFoundWhenProviderMissing() = runTest {
        val ex = assertFailsWith<AiException> {
            runLoop(
                request = GenerateTextRequest(
                    model = AiModelId("missing", "x"),
                    messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                ),
                script = emptyList(),
                defaultModel = null,
            )
        }
        assertTrue(ex.error is AiError.ModelNotFound)
    }

    @Test fun usageRecorderCalledPerRound() = runTest {
        val req = GenerateTextRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            metadata = mapOf("requestId" to "r-42", "userId" to "u1"),
            tools = listOf(AiToolDefinition("t", "", "{}", AiToolExecutor { _ -> "ok" })),
        )
        val recorder = CapturingRecorder()
        runLoop(
            request = req,
            script = listOf(
                textReply("", calls = listOf(AiToolCall("c1", "t", "{}"))),
                textReply("done"),
            ),
            recorder = recorder,
        )
        assertEquals(2, recorder.events.size)
        // Both events get the same requestId and request metadata
        assertEquals("r-42", recorder.events[0].requestId)
        assertEquals("r-42", recorder.events[1].requestId)
        assertEquals(mapOf("requestId" to "r-42", "userId" to "u1"), recorder.events[0].requestMetadata)
        // Round numbers
        assertEquals(1, recorder.events[0].round)
        assertEquals(2, recorder.events[1].round)
        // Both success
        assertTrue(recorder.events.all { it.success })
    }
}
```

- [ ] **Step 3: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.ToolLoopTest" 2>&1 | tail -10`

Expected: FAIL with unresolved `runToolLoop` reference.

- [ ] **Step 4: Implement `ToolLoop.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/ToolLoop.kt
package neton.ai.internal

import kotlinx.coroutines.CancellationException
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiModelId
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolResult
import neton.ai.AiUsage
import neton.ai.GenerateTextRequest
import neton.ai.GenerateTextResult
import neton.ai.isFallbackEligible
import neton.ai.kind
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageEvent
import neton.ai.usage.AiUsageRecorder

/**
 * Non-streaming tool loop per spec §3.7. See ToolLoopTest for the exhaustive branch coverage.
 *
 * High-level:
 *   candidates = router.resolve(request.model, request.modelPolicy)
 *   for each candidate in order:
 *     for round in 1..maxToolRounds:
 *       try: resp = model.generate(...)
 *       record usage
 *       if no toolCalls → return success
 *       if any tool has no executor → return early with FinishReason.ToolCalls
 *       execute tools, append result messages, continue
 *     exhausted → return with FinishReason.ToolCalls
 *   throw lastError
 *
 * Fallback rules:
 *   - round 1 + fallback-eligible error + more candidates → continue to next candidate
 *   - round >= 2 → never fallback (state already accumulated)
 *   - non-fallback-eligible error (auth/semantic) → propagate immediately
 */
internal suspend fun runToolLoop(
    request: GenerateTextRequest,
    registry: ProviderRegistry,
    router: ModelRouter,
    recorder: AiUsageRecorder,
): GenerateTextResult {
    val candidates: List<AiModelId> = router.resolve(request.model, request.modelPolicy)
    if (candidates.isEmpty()) {
        throw AiException(AiError.InvalidRequest("Router returned no candidates"))
    }

    val requestId: String? = request.metadata["requestId"] ?: request.metadata["traceId"]
    var lastError: AiError? = null

    for ((candidateIdx, modelId) in candidates.withIndex()) {
        val provider = registry.get(modelId.providerId)
        val model = provider?.textModel(modelId.modelName)
        if (model == null) {
            lastError = AiError.ModelNotFound(
                modelId = modelId.toString(),
                message = "Provider '${modelId.providerId}' or model '${modelId.modelName}' not registered",
            )
            // ModelNotFound is NOT fallback-eligible per AiError.isFallbackEligible.
            // But router gave us multiple candidates intentionally (policy); if one is misconfigured,
            // try the next so the request can still succeed (fail-soft on policy misconfiguration).
            if (request.modelPolicy != null && candidateIdx < candidates.lastIndex) continue
            throw AiException(lastError)
        }

        val workingMessages = request.messages.toMutableList()
        val accumulatedCalls = mutableListOf<AiToolCall>()
        val accumulatedResults = mutableListOf<AiToolResult>()
        val perRoundUsage = mutableListOf<AiUsage?>()
        var lastResp: ProviderCallResponse? = null

        roundLoop@ for (round in 1..request.maxToolRounds) {
            val startMs = currentTimeMillisCompat()
            val resp: ProviderCallResponse = try {
                model.generate(buildSpiRequest(request, workingMessages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                val err = e.error
                // Round 1 + fallback eligible + more candidates → fall back to next candidate
                val canFallback = round == 1 && err.isFallbackEligible() &&
                    candidateIdx < candidates.lastIndex && request.modelPolicy != null
                recorder.record(AiUsageEvent(
                    requestId = requestId,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    usage = null,
                    round = round,
                    requestMetadata = request.metadata,
                    timestampEpochMillis = startMs,
                    durationMillis = currentTimeMillisCompat() - startMs,
                    finishReason = null,
                    success = false,
                    errorKind = err.kind,
                ))
                if (canFallback) {
                    lastError = err
                    continue@roundLoop.also { /* unreachable */ }
                        .also { /* placeholder so the for-iterator advances past this candidate */ }
                        // Switch candidate: break out of inner loop, outer for picks next modelId
                }
                throw e
            }
            recorder.record(AiUsageEvent(
                requestId = requestId,
                providerId = modelId.providerId,
                modelName = modelId.modelName,
                usage = resp.usage,
                round = round,
                requestMetadata = request.metadata,
                timestampEpochMillis = startMs,
                durationMillis = currentTimeMillisCompat() - startMs,
                finishReason = resp.finishReason,
                success = true,
                errorKind = null,
            ))
            perRoundUsage += resp.usage
            lastResp = resp

            if (resp.toolCalls.isEmpty()) {
                return GenerateTextResult(
                    text = resp.text,
                    message = resp.message,
                    toolCalls = accumulatedCalls,
                    toolResults = accumulatedResults,
                    usage = aggregateUsage(perRoundUsage),
                    finishReason = resp.finishReason,
                    providerId = modelId.providerId,
                    modelName = modelId.modelName,
                    rounds = round,
                )
            }

            accumulatedCalls += resp.toolCalls
            workingMessages += resp.message

            for (call in resp.toolCalls) {
                val def = request.tools.firstOrNull { it.name == call.name }
                if (def?.executor == null) {
                    // No local executor → return early; caller handles tool calls
                    return GenerateTextResult(
                        text = resp.text,
                        message = resp.message,
                        toolCalls = accumulatedCalls,
                        toolResults = accumulatedResults,
                        usage = aggregateUsage(perRoundUsage),
                        finishReason = AiFinishReason.ToolCalls,
                        providerId = modelId.providerId,
                        modelName = modelId.modelName,
                        rounds = round,
                    )
                }
                val result = try {
                    AiToolResult(call.id, def.executor.execute(call.argumentsJson), isError = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
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

        // maxToolRounds exhausted → return last response with ToolCalls finish reason
        if (lastResp != null) {
            return GenerateTextResult(
                text = lastResp.text,
                message = lastResp.message,
                toolCalls = accumulatedCalls,
                toolResults = accumulatedResults,
                usage = aggregateUsage(perRoundUsage),
                finishReason = AiFinishReason.ToolCalls,
                providerId = modelId.providerId,
                modelName = modelId.modelName,
                rounds = request.maxToolRounds,
            )
        }
        // Fallback path: continue to next candidate (loop again)
    }

    throw AiException(lastError ?: AiError.Unknown("No candidate model succeeded", null))
}

private fun buildSpiRequest(request: GenerateTextRequest, messages: List<AiMessage>): ProviderCallRequest =
    ProviderCallRequest(
        messages = messages,
        tools = request.tools,
        toolChoice = request.toolChoice,
        temperature = request.temperature,
        maxTokens = request.maxTokens,
        topP = request.topP,
        stopSequences = request.stopSequences,
        metadata = request.metadata,
    )

/** KMP-safe wall clock. kotlinx-datetime would be cleaner but adds a transitive dep we don't need. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal expect fun currentTimeMillisCompat(): Long
```

**IMPORTANT** — the pseudocode `continue@roundLoop` above does NOT achieve "fall to next outer candidate" in Kotlin. Use a `break` out of the inner `for (round)` loop, leaving the outer `for (candidateIdx, modelId)` to advance naturally. Rewrite:

Replace the `if (canFallback)` block with:
```kotlin
if (canFallback) {
    lastError = err
    break  // exits inner round loop, outer for advances to next candidate
}
```

(Editor: when implementing, write this cleaner form. The pseudocode above shows intent; the Kotlin idiom is `break`.)

**`currentTimeMillisCompat` expect/actual**: create per-platform actuals:
- `commonMain`: `internal expect fun currentTimeMillisCompat(): Long`
- `posixMain` (covers macosMain + linuxMain): `internal actual fun currentTimeMillisCompat(): Long = kotlin.system.getTimeMillis()`
  - Or use `platform.posix.clock_gettime` if `kotlin.system.getTimeMillis()` is unavailable on Native KMP. Verify during implementation.
- `mingwX64Main`: similar
- If a single `internal actual` in `nativeMain` works for all 5 native targets, use that.

Simpler alternative if `kotlin.system.getTimeMillis()` is unavailable on KMP Native: import `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` — but this adds `kotlinx-datetime` as a dep. **Recommended**: just add `kotlinx-datetime` to `commonMain` deps and use `Clock.System.now().toEpochMilliseconds()` directly in common code, drop the expect/actual.

If you go with `kotlinx-datetime`:
- Add `implementation(libs.kotlinx.datetime)` to `commonMain` in `neton-ai/build.gradle.kts`
- Replace `currentTimeMillisCompat()` calls with `Clock.System.now().toEpochMilliseconds()`
- Remove the expect/actual declarations

This is the cleaner path; let the implementer choose during execution and note the choice in the implementer report.

- [ ] **Step 5: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.ToolLoopTest" 2>&1 | tail -15`

Expected: 10 tests pass (all branches of spec §3.7 non-streaming tool loop).

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultProviderRegistry.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/ToolLoop.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/ToolLoopTest.kt
# If kotlinx-datetime was added:
# git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/build.gradle.kts gradle/libs.versions.toml
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add ToolLoop non-streaming core + DefaultProviderRegistry with 10 scripted-provider tests"
```

---

## Task 10: `AiClient` interface + `AiClientFactory` + `DefaultAiClient` + standalone `Companion.create`

This is the **dual-usage public entry point** (Mode 1 — standalone). Mirrors PR0's `NetonHttpClient.create` pattern but for AI.

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultAiClient.kt`

(`AiConfig` + providers builders come in Task 15 — to make `Companion.create` compile in Task 10 we either (a) move `AiConfig` creation up here, or (b) add a TODO placeholder. **Plan choice (b)**: Task 10 ships `AiClient.create { ... }` with a `TODO("Task 15 — AiConfig DSL not implemented")` in the body, identical pattern to PR0 Task 7 → Task 12 chain. Task 17 closes the TODO when AiComponent + AiClientFactory wire is complete.)

Actually — **revised choice (c)**: since `AiClient.create` needs `AiConfig`, `ProvidersBuilder`, etc., and those are heavy (Task 15-17), it's cleaner to:
- Task 10: ship `AiClient` interface only (no `Companion.create` yet), plus `DefaultAiClient(...)` impl that takes resolved dependencies (registry, router, recorder, httpClient).
- Task 17 (AiComponent + AiClientFactory): adds the standalone `AiClient.Companion.create { ... }` factory, since it depends on AiConfig (Task 15) anyway.

So Task 10 = interface + concrete impl only. Standalone factory deferred to Task 17.

- [ ] **Step 1: Create `AiClient.kt` (interface only)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt
package neton.ai

import neton.ai.builder.GenerateTextRequestBuilder

/**
 * Provider-neutral AI client. Public API of neton-ai.
 *
 * **Dual usage**:
 *   1. Standalone (any KMP project): `val ai = AiClient.create { httpClient = ...; providers { ... }; routing { ... } }`
 *      (Companion factory added in Task 17.)
 *   2. Neton Framework component: `Neton.run { httpClient { }; ai { ... } }`; downstream
 *      code uses `ctx.get(AiClient::class)`.
 *
 * v0.1 ships only generateText (non-streaming). streamText / embed land in PR2 / PR3.
 */
interface AiClient {
    /** DSL form. Internally builds a GenerateTextRequest. */
    suspend fun generateText(block: GenerateTextRequestBuilder.() -> Unit): GenerateTextResult

    /** Request-object form. Useful for callers doing request preprocessing / caching. */
    suspend fun generateText(request: GenerateTextRequest): GenerateTextResult

    // Note: streamText / embed are added in PR2 / PR3. AiClient interface evolves additively.

    /** Release resources held by this client (if any — most are managed by NetonHttpClient). */
    suspend fun close()

    companion object {
        // Standalone Companion.create factory will be added in Task 17 once AiConfig DSL exists.
    }
}
```

- [ ] **Step 2: Create `DefaultAiClient.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultAiClient.kt
package neton.ai.internal

import neton.ai.AiClient
import neton.ai.GenerateTextRequest
import neton.ai.GenerateTextResult
import neton.ai.builder.GenerateTextRequestBuilder
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder

internal class DefaultAiClient(
    private val registry: ProviderRegistry,
    private val router: ModelRouter,
    private val recorder: AiUsageRecorder,
) : AiClient {

    override suspend fun generateText(block: GenerateTextRequestBuilder.() -> Unit): GenerateTextResult {
        val builder = GenerateTextRequestBuilder().apply(block)
        // Cannot call internal builder.build() from outside its package; expose via reflection-like helper.
        return generateText(buildInternal(builder))
    }

    override suspend fun generateText(request: GenerateTextRequest): GenerateTextResult =
        runToolLoop(request, registry, router, recorder)

    override suspend fun close() {
        // DefaultAiClient doesn't own the NetonHttpClient; it's passed in via providers
        // (each provider holds a reference). Providers don't own the http client either — the
        // standalone factory (Task 17) owns it and closes it explicitly. Nothing to do here.
    }
}

/**
 * Bridge to call the package-internal `GenerateTextRequestBuilder.build()` from this file
 * (which is in a different subpackage). Implemented by exposing a top-level internal helper
 * in neton.ai.builder package — see Task 5 builder file (add this if not already there):
 *
 *   @PublishedApi internal fun GenerateTextRequestBuilder.toRequest(): GenerateTextRequest = build()
 */
private fun buildInternal(builder: GenerateTextRequestBuilder): GenerateTextRequest =
    builder.toRequest()
```

- [ ] **Step 3: Add `toRequest()` helper to `GenerateTextRequestBuilder.kt`**

In `neton-ai/src/commonMain/kotlin/neton/ai/builder/GenerateTextRequestBuilder.kt`, add at the bottom of the file:

```kotlin
/** Internal bridge so DefaultAiClient (different subpackage) can call the package-internal build(). */
@PublishedApi
internal fun GenerateTextRequestBuilder.toRequest(): GenerateTextRequest = build()
```

(Alternative: make `build()` itself `@PublishedApi internal`. Either works; the `toRequest()` extension is slightly clearer.)

- [ ] **Step 4: Create `AiClientFactory.kt` (skeleton; full impl in Task 17)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt
package neton.ai.internal

import neton.ai.AiClient
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder

/**
 * Internal entry point for both standalone (Task 17 AiClient.Companion.create) and component
 * (Task 18 AiComponent) paths to construct AiClient from already-resolved dependencies.
 *
 * Task 17 adds the higher-level `createFromConfig(EffectiveAiConfig, NetonHttpClient, ...)` overload
 * which builds the registry from provider specs.
 */
internal object AiClientFactory {
    fun create(
        registry: ProviderRegistry,
        router: ModelRouter,
        recorder: AiUsageRecorder,
    ): AiClient = DefaultAiClient(registry, router, recorder)
}
```

- [ ] **Step 5: Verify compilation**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/DefaultAiClient.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/builder/GenerateTextRequestBuilder.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiClient interface + DefaultAiClient + AiClientFactory (standalone create in Task 17)"
```

---

## Task 11: OpenAI-compatible — Wire DTOs + `OpenAiCompatibleRequestMapper` (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireRequest.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapper.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapperTest.kt`

- [ ] **Step 1: Wire DTOs** (internal kotlinx.serialization shapes — never leak)

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireRequest.kt
package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: List<String>? = null,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,  // string OR object — keep flexible
    val stream: Boolean? = null,  // false for non-stream; PR2 sets true
)

@Serializable
internal data class OpenAiMessage(
    val role: String,                            // "system" | "user" | "assistant" | "tool"
    val content: String? = null,                 // null when assistant has only tool_calls
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
internal data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunctionDef,
)

@Serializable
internal data class OpenAiFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement,                 // raw JSON Schema from AiToolDefinition.inputSchemaJson
)

@Serializable
internal data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

@Serializable
internal data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,                       // JSON string per OpenAI spec
)
```

- [ ] **Step 2: Write failing RequestMapper tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapperTest.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.provider.ProviderCallRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleRequestMapperTest {

    private val mapper = OpenAiCompatibleRequestMapper()
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private fun req(messages: List<AiMessage> = emptyList(),
                    tools: List<AiToolDefinition> = emptyList(),
                    toolChoice: ToolChoice = ToolChoice.Auto,
                    temperature: Double? = null) =
        ProviderCallRequest(messages, tools, toolChoice, temperature, null, null, emptyList(), emptyMap())

    @Test fun systemUserAssistantMessagesMap() {
        val out: OpenAiChatRequest = mapper.toWire("gpt-4o-mini", req(messages = listOf(
            AiMessage(AiRole.System, listOf(AiContent.Text("sys"))),
            AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
            AiMessage(AiRole.Assistant, listOf(AiContent.Text("hello"))),
        )))
        assertEquals("gpt-4o-mini", out.model)
        assertEquals(3, out.messages.size)
        assertEquals("system", out.messages[0].role)
        assertEquals("sys", out.messages[0].content)
        assertEquals("user", out.messages[1].role)
        assertEquals("assistant", out.messages[2].role)
    }

    @Test fun multipleTextContentConcatenatedWithNewline() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(AiRole.User, listOf(AiContent.Text("line1"), AiContent.Text("line2"))),
        )))
        assertEquals("line1\nline2", out.messages.single().content)
    }

    @Test fun assistantToolCallsMapToToolCallsField() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(
                role = AiRole.Assistant,
                content = emptyList(),
                toolCalls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}""")),
            ),
        )))
        val msg = out.messages.single()
        assertEquals("assistant", msg.role)
        assertNull(msg.content)
        assertEquals(1, msg.toolCalls?.size)
        assertEquals("c1", msg.toolCalls!![0].id)
        assertEquals("get_balance", msg.toolCalls[0].function.name)
        assertEquals("""{"userId":7}""", msg.toolCalls[0].function.arguments)
    }

    @Test fun toolRoleMessageMapsWithToolCallId() {
        val out = mapper.toWire("m", req(messages = listOf(
            AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text("""{"balance":42}""")),
                toolCallId = "c1",
            ),
        )))
        val msg = out.messages.single()
        assertEquals("tool", msg.role)
        assertEquals("c1", msg.toolCallId)
        assertEquals("""{"balance":42}""", msg.content)
    }

    @Test fun toolDefinitionsMapToToolsField() {
        val out = mapper.toWire("m", req(tools = listOf(
            AiToolDefinition(
                name = "get_balance",
                description = "Get user balance",
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}""",
            ),
        )))
        assertEquals(1, out.tools?.size)
        val tool = out.tools!!.single()
        assertEquals("function", tool.type)
        assertEquals("get_balance", tool.function.name)
        assertEquals("Get user balance", tool.function.description)
        // parameters is raw JSON tree
        val params = tool.function.parameters as JsonObject
        assertEquals(JsonPrimitive("object"), params["type"])
    }

    @Test fun toolChoiceAutoMapsToStringAuto() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Auto))
        assertEquals(JsonPrimitive("auto"), out.toolChoice)
    }

    @Test fun toolChoiceNoneMapsToStringNone() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.None))
        assertEquals(JsonPrimitive("none"), out.toolChoice)
    }

    @Test fun toolChoiceRequiredMapsToStringRequired() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Required))
        assertEquals(JsonPrimitive("required"), out.toolChoice)
    }

    @Test fun toolChoiceNamedMapsToFunctionObject() {
        val out = mapper.toWire("m", req(toolChoice = ToolChoice.Named("my_tool")))
        val tc = out.toolChoice as JsonObject
        assertEquals(JsonPrimitive("function"), tc["type"])
        assertEquals(JsonPrimitive("my_tool"), (tc["function"] as JsonObject)["name"])
    }

    @Test fun nonStreamRequestHasStreamFalseOrUnset() {
        val out = mapper.toWire("m", req())
        assertTrue(out.stream == null || out.stream == false)
    }

    @Test fun roundTripsThroughJsonWithoutLoss() {
        // Encode → decode → re-encode produces same JSON (no field-level drift)
        val original = mapper.toWire("m", req(messages = listOf(
            AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
        )))
        val encoded = json.encodeToString(OpenAiChatRequest.serializer(), original)
        val decoded = json.decodeFromString(OpenAiChatRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
```

- [ ] **Step 3: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.adapter.openaicompatible.OpenAiCompatibleRequestMapperTest" 2>&1 | tail -10`

- [ ] **Step 4: Implement `OpenAiCompatibleRequestMapper.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapper.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.ToolChoice
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.adapter.openaicompatible.dto.OpenAiFunctionCall
import neton.ai.adapter.openaicompatible.dto.OpenAiFunctionDef
import neton.ai.adapter.openaicompatible.dto.OpenAiMessage
import neton.ai.adapter.openaicompatible.dto.OpenAiTool
import neton.ai.adapter.openaicompatible.dto.OpenAiToolCall
import neton.ai.provider.ProviderCallRequest

internal class OpenAiCompatibleRequestMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    fun toWire(modelName: String, req: ProviderCallRequest): OpenAiChatRequest = OpenAiChatRequest(
        model = modelName,
        messages = req.messages.map(::messageToWire),
        temperature = req.temperature,
        maxTokens = req.maxTokens,
        topP = req.topP,
        stop = req.stopSequences.takeIf { it.isNotEmpty() },
        tools = req.tools.takeIf { it.isNotEmpty() }?.map { def ->
            OpenAiTool(function = OpenAiFunctionDef(
                name = def.name,
                description = def.description,
                parameters = json.parseToJsonElement(def.inputSchemaJson),
            ))
        },
        toolChoice = req.toolChoice.takeIf { req.tools.isNotEmpty() || it !is ToolChoice.Auto }?.let(::toolChoiceToWire),
        stream = false,
    )

    private fun messageToWire(m: AiMessage): OpenAiMessage = OpenAiMessage(
        role = when (m.role) {
            AiRole.System -> "system"
            AiRole.User -> "user"
            AiRole.Assistant -> "assistant"
            AiRole.Tool -> "tool"
        },
        content = m.content
            .filterIsInstance<AiContent.Text>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotEmpty() || m.toolCalls.isEmpty() },  // null when assistant has only tool_calls
        toolCallId = m.toolCallId,
        toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { tc ->
            OpenAiToolCall(
                id = tc.id,
                function = OpenAiFunctionCall(name = tc.name, arguments = tc.argumentsJson),
            )
        },
    )

    private fun toolChoiceToWire(c: ToolChoice): kotlinx.serialization.json.JsonElement = when (c) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", c.name) })
        }
    }
}
```

- [ ] **Step 5: Run tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.adapter.openaicompatible.OpenAiCompatibleRequestMapperTest" 2>&1 | tail -10`

Expected: 10 tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/ \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapper.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleRequestMapperTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add OpenAI-compatible wire DTOs + RequestMapper with 10 tests"
```

---

## Task 12: OpenAI-compatible — Response wire DTOs + `OpenAiCompatibleResponseMapper` + `OpenAiCompatibleTextModel` + `OpenAiCompatibleProvider` + MockEngine integration test

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireResponse.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapper.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleProvider.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapperTest.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleIntegrationTest.kt`

- [ ] **Step 1: Response wire DTOs**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/dto/OpenAiWireResponse.kt
package neton.ai.adapter.openaicompatible.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null,
)

@Serializable
internal data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OpenAiResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiResponseToolCall>? = null,
)

@Serializable
internal data class OpenAiResponseToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiResponseFunctionCall,
)

@Serializable
internal data class OpenAiResponseFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/** Error envelope per OpenAI spec (also returned by DeepSeek / Qwen compat). */
@Serializable
internal data class OpenAiErrorEnvelope(val error: OpenAiErrorBody)

@Serializable
internal data class OpenAiErrorBody(
    val message: String,
    val type: String? = null,
    val code: String? = null,
)
```

- [ ] **Step 2: Write failing ResponseMapper tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapperTest.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiRole
import neton.ai.AiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleResponseMapperTest {

    private val mapper = OpenAiCompatibleResponseMapper()
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun simpleTextResponseMapped() {
        val body = """
            {"id":"x","model":"gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiRole.Assistant, resp.message.role)
        assertEquals("hello", resp.text)
        assertEquals(AiFinishReason.Stop, resp.finishReason)
        assertEquals(AiUsage(5, 2, 7), resp.usage)
        assertTrue(resp.toolCalls.isEmpty())
    }

    @Test fun toolCallsMappedToAiToolCalls() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_balance","arguments":"{\"userId\":7}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()
        val resp = mapper.fromWireBody(body)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("c1", resp.toolCalls[0].id)
        assertEquals("get_balance", resp.toolCalls[0].name)
        assertEquals("""{"userId":7}""", resp.toolCalls[0].argumentsJson)
    }

    @Test fun finishReasonStopMapsToStop() = assertFinish("stop", AiFinishReason.Stop)
    @Test fun finishReasonLengthMapsToLength() = assertFinish("length", AiFinishReason.Length)
    @Test fun finishReasonToolCallsMapsToToolCalls() = assertFinish("tool_calls", AiFinishReason.ToolCalls)
    @Test fun finishReasonFunctionCallLegacyMapsToToolCalls() = assertFinish("function_call", AiFinishReason.ToolCalls)
    @Test fun finishReasonContentFilterMapsToContentFilter() = assertFinish("content_filter", AiFinishReason.ContentFilter)
    @Test fun finishReasonOtherStringMapsToOther() = assertFinish("weird", AiFinishReason.Other)
    @Test fun finishReasonNullMapsToOther() = assertFinish(null, AiFinishReason.Other)

    private fun assertFinish(wire: String?, expected: AiFinishReason) {
        val finishField = wire?.let { "\"finish_reason\":\"$it\"" } ?: "\"finish_reason\":null"
        val body = """{"choices":[{"message":{"role":"assistant","content":"x"},$finishField}]}"""
        assertEquals(expected, mapper.fromWireBody(body).finishReason)
    }

    @Test fun usageAbsentYieldsNullUsage() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}"""
        assertEquals(null, mapper.fromWireBody(body).usage)
    }

    // ---- HTTP error mapping ----

    @Test fun status401MapsToUnauthorized() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(401, """{"error":{"message":"bad key"}}""") }
        assertTrue(ex.error is AiError.Unauthorized)
    }

    @Test fun status403MapsToForbidden() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(403, """{"error":{"message":"x"}}""") }
        assertTrue(ex.error is AiError.Forbidden)
    }

    @Test fun status429MapsToRateLimited() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(429, """{"error":{"message":"slow down"}}""") }
        assertTrue(ex.error is AiError.RateLimited)
    }

    @Test fun status500MapsToServerError() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(500, "internal") }
        assertTrue(ex.error is AiError.ServerError)
        assertEquals(500, (ex.error as AiError.ServerError).statusCode)
    }

    @Test fun status400ContextLengthExceededMaps() {
        val body = """{"error":{"message":"context length exceeded","type":"invalid_request_error","code":"context_length_exceeded"}}"""
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(400, body) }
        assertTrue(ex.error is AiError.ContextLengthExceeded)
    }

    @Test fun status404ModelNotFoundMaps() {
        val body = """{"error":{"message":"model not found","code":"model_not_found"}}"""
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(404, body) }
        assertTrue(ex.error is AiError.ModelNotFound)
    }

    @Test fun status400OtherMapsToInvalidRequest() {
        val ex = assertFailsWith<AiException> { mapper.errorFromStatus(400, """{"error":{"message":"bad"}}""") }
        assertTrue(ex.error is AiError.InvalidRequest)
    }
}
```

- [ ] **Step 3: Run tests (expect failure)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.adapter.openaicompatible.OpenAiCompatibleResponseMapperTest" 2>&1 | tail -10`

- [ ] **Step 4: Implement `OpenAiCompatibleResponseMapper.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleResponseMapper.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.adapter.openaicompatible.dto.OpenAiChatResponse
import neton.ai.adapter.openaicompatible.dto.OpenAiErrorEnvelope
import neton.ai.provider.ProviderCallResponse

internal class OpenAiCompatibleResponseMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    fun fromWireBody(body: String): ProviderCallResponse {
        val resp = try {
            json.decodeFromString(OpenAiChatResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw AiException(AiError.Unknown("Invalid OpenAI response JSON: ${e.message}", e))
        }
        val choice = resp.choices.firstOrNull()
            ?: throw AiException(AiError.Unknown("OpenAI response has no choices", null))
        val text = choice.message.content.orEmpty()
        val toolCalls = choice.message.toolCalls.orEmpty().map { tc ->
            AiToolCall(id = tc.id, name = tc.function.name, argumentsJson = tc.function.arguments)
        }
        return ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            toolCalls = toolCalls,
            usage = resp.usage?.let { AiUsage(it.promptTokens, it.completionTokens, it.totalTokens) },
            finishReason = mapFinishReason(choice.finishReason),
        )
    }

    fun errorFromStatus(statusCode: Int, body: String): Nothing {
        val parsedMessage = tryParseErrorMessage(body) ?: body.take(500)
        val parsedCode = tryParseErrorCode(body)
        throw AiException(when (statusCode) {
            401 -> AiError.Unauthorized(parsedMessage)
            403 -> AiError.Forbidden(parsedMessage)
            429 -> AiError.RateLimited(retryAfterMillis = null, message = parsedMessage)
            in 500..599 -> AiError.ServerError(statusCode, parsedMessage)
            400 -> when (parsedCode) {
                "context_length_exceeded" -> AiError.ContextLengthExceeded(parsedMessage)
                else -> AiError.InvalidRequest(parsedMessage)
            }
            404 -> when (parsedCode) {
                "model_not_found" -> AiError.ModelNotFound("unknown", parsedMessage)
                else -> AiError.InvalidRequest(parsedMessage)
            }
            else -> AiError.Unknown("HTTP $statusCode: $parsedMessage", null)
        })
    }

    private fun mapFinishReason(s: String?): AiFinishReason = when (s) {
        "stop" -> AiFinishReason.Stop
        "length" -> AiFinishReason.Length
        "tool_calls", "function_call" -> AiFinishReason.ToolCalls
        "content_filter" -> AiFinishReason.ContentFilter
        else -> AiFinishReason.Other
    }

    private fun tryParseErrorMessage(body: String): String? = try {
        json.decodeFromString(OpenAiErrorEnvelope.serializer(), body).error.message
    } catch (_: Throwable) { null }

    private fun tryParseErrorCode(body: String): String? = try {
        json.decodeFromString(OpenAiErrorEnvelope.serializer(), body).error.code
    } catch (_: Throwable) { null }
}
```

- [ ] **Step 5: Run mapper tests (expect pass: 17 tests)**

- [ ] **Step 6: Implement `OpenAiCompatibleTextModel.kt` + `OpenAiCompatibleProvider.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt
package neton.ai.adapter.openaicompatible

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.adapter.openaicompatible.dto.OpenAiChatRequest
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest

internal class OpenAiCompatibleTextModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String?,
    private val defaultHeaders: Map<String, String>,
    private val requestMapper: OpenAiCompatibleRequestMapper = OpenAiCompatibleRequestMapper(),
    private val responseMapper: OpenAiCompatibleResponseMapper = OpenAiCompatibleResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) : AiTextModel {
    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(OpenAiChatRequest.serializer(), wire)
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            organization?.let { put("OpenAI-Organization", it) }
            putAll(defaultHeaders)
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = "$baseUrl/chat/completions",
                headers = headers,
                body = NetonHttpBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.NetonHttpError.Http -> throw IllegalStateException("Http error should not occur here (expectSuccess=false)")
                is neton.http.client.NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        return responseMapper.fromWireBody(resp.body)
    }
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleProvider.kt
package neton.ai.adapter.openaicompatible

import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.http.client.NetonHttpClient

class OpenAiCompatibleProvider(
    override val id: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val organization: String? = null,
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel = OpenAiCompatibleTextModel(
        providerId = id,
        modelName = modelName,
        httpClient = httpClient,
        baseUrl = baseUrl,
        apiKey = apiKey,
        organization = organization,
        defaultHeaders = defaultHeaders,
    )
    override fun streamingTextModel(modelName: String): AiStreamingTextModel? = null  // PR2
    override fun embeddingModel(modelName: String): AiEmbeddingModel? = null          // PR3
}
```

- [ ] **Step 7: Integration test using MockEngine + NetonHttpClient (cannot inject MockEngine through NetonHttpClient.create currently — we either need PR0 to expose a test-friendly factory, OR we wrap MockEngine via the engineFactory pattern proven in PR0's CancellationTest)**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleIntegrationTest.kt
package neton.ai.adapter.openaicompatible

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.ai.AiContent
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolDefinition
import neton.ai.AiToolExecutor
import neton.ai.AiUsage
import neton.ai.ToolChoice
import neton.ai.provider.ProviderCallRequest
import neton.http.client.NetonHttpClient
import neton.http.client.internal.DefaultNetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatibleIntegrationTest {

    private fun httpClient(engine: MockEngine): NetonHttpClient =
        DefaultNetonHttpClient(engineFactory = factoryOf(engine))

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun nonStreamGenerateMapsRequestAndResponseEndToEnd() = runTest {
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { req ->
            capturedUrl = req.url.toString()
            capturedAuth = req.headers["Authorization"]
            capturedBody = req.body.toByteArray().decodeToString()
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider(
            id = "openai", httpClient = client,
            baseUrl = "https://api.openai.com/v1", apiKey = "sk-test",
        )
        val model = provider.textModel("gpt-4o-mini")
        val resp = model.generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            tools = emptyList(), toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
        assertEquals("Bearer sk-test", capturedAuth)
        assertTrue(capturedBody!!.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(capturedBody!!.contains("\"role\":\"user\""))
        assertEquals("hello", resp.text)
        assertEquals(AiFinishReason.Stop, resp.finishReason)
        assertEquals(AiUsage(5, 2, 7), resp.usage)
        client.close()
    }

    @Test fun nonStreamGenerateMapsToolCallResponse() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_balance","arguments":"{\"userId\":7}"}}]},"finish_reason":"tool_calls"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://x", "sk")
        val resp = provider.textModel("m").generate(ProviderCallRequest(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("balance?")))),
            tools = listOf(AiToolDefinition("get_balance", "", "{}", AiToolExecutor { "" })),
            toolChoice = ToolChoice.Auto,
            temperature = null, maxTokens = null, topP = null,
            stopSequences = emptyList(), metadata = emptyMap(),
        ))
        assertEquals(1, resp.toolCalls.size)
        assertEquals("get_balance", resp.toolCalls[0].name)
        assertEquals("""{"userId":7}""", resp.toolCalls[0].argumentsJson)
        assertEquals(AiFinishReason.ToolCalls, resp.finishReason)
        client.close()
    }

    @Test fun http429MapsToAiErrorRateLimited() = runTest {
        val engine = MockEngine { _ ->
            respond("""{"error":{"message":"slow down"}}""", HttpStatusCode.TooManyRequests)
        }
        val client = httpClient(engine)
        val provider = OpenAiCompatibleProvider("openai", client, "https://x", "sk")
        val model = provider.textModel("m")
        val ex = kotlin.test.assertFailsWith<neton.ai.AiException> {
            model.generate(ProviderCallRequest(
                messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
                tools = emptyList(), toolChoice = ToolChoice.Auto,
                temperature = null, maxTokens = null, topP = null,
                stopSequences = emptyList(), metadata = emptyMap(),
            ))
        }
        assertTrue(ex.error is neton.ai.AiError.RateLimited)
        client.close()
    }
}
```

NOTE: This integration test accesses `neton.http.client.internal.DefaultNetonHttpClient` (internal to `neton-http-client`). Two options:
- (a) `DefaultNetonHttpClient` is `internal` to the `neton-http-client` module; tests in `neton-ai` cannot access it directly. **Solution**: add a public test-friendly factory to `neton-http-client` like `NetonHttpClient.Companion.createForTesting(engineFactory: HttpClientEngineFactory<*>, config: HttpClientConfig)`. **OR**:
- (b) Move integration tests to `neton-http-client`'s commonTest (but those are about HTTP, not AI; bad fit).

**Recommended**: Add `NetonHttpClient.Companion.createWithEngine(engineFactory, block)` to `neton-http-client` as a TEST-VISIBLE factory (marked `@PublishedApi` or annotated for test-only — or just public with a KDoc warning that it's for testing/integration; production code should use `create { ... }`).

This is a small back-edit to `neton-http-client` — implement as Step 7a below.

- [ ] **Step 7a: Add test-injectable engine factory to `neton-http-client/NetonHttpClient.kt`**

In `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpClient.kt`, expand the `companion object`:

```kotlin
companion object {
    fun create(block: HttpClientConfig.() -> Unit = {}): NetonHttpClient {
        val cfg = HttpClientConfig().apply(block)
        val errors = cfg.validate()
        if (errors.isNotEmpty()) {
            throw NetonHttpException(NetonHttpError.Unknown(
                "Invalid HTTP client config: ${errors.joinToString()}", null,
            ))
        }
        return DefaultNetonHttpClient(defaultTimeout = cfg.toEffectiveTimeout())
    }

    /**
     * Construct with a caller-supplied Ktor engine factory. Intended for tests (MockEngine)
     * and advanced production cases (custom engine config). Use [create] for normal usage.
     */
    fun createWithEngine(
        engineFactory: io.ktor.client.engine.HttpClientEngineFactory<*>,
        block: HttpClientConfig.() -> Unit = {},
    ): NetonHttpClient {
        val cfg = HttpClientConfig().apply(block)
        val errors = cfg.validate()
        if (errors.isNotEmpty()) {
            throw NetonHttpException(NetonHttpError.Unknown(
                "Invalid HTTP client config: ${errors.joinToString()}", null,
            ))
        }
        return DefaultNetonHttpClient(engineFactory = engineFactory, defaultTimeout = cfg.toEffectiveTimeout())
    }
}
```

This **does** leak `io.ktor.client.engine.HttpClientEngineFactory` to the public API. That's acceptable here because it's the canonical Ktor extension point for engine injection and we can't hide it without re-creating an entire engine abstraction layer (over-engineering). Document the trade-off in commit message.

Then in `OpenAiCompatibleIntegrationTest.kt`, replace `DefaultNetonHttpClient(...)` calls with:
```kotlin
private fun httpClient(engine: MockEngine): NetonHttpClient =
    NetonHttpClient.createWithEngine(factoryOf(engine))
```

Commit this `neton-http-client` change separately:
```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpClient.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(http-client): expose createWithEngine(engineFactory) for test injection (PR1 prep)"
```

- [ ] **Step 8: Run integration tests (expect pass)**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.adapter.openaicompatible.OpenAiCompatibleIntegrationTest" 2>&1 | tail -10`

Expected: 3 tests pass.

- [ ] **Step 9: Commit OpenAi-compat impl + integration test**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/ \
    neton-ai/src/commonTest/kotlin/neton/ai/adapter/openaicompatible/
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add OpenAI-compatible adapter (Response DTO/Mapper + TextModel + Provider) + 17 mapper tests + 3 MockEngine integration tests"
```

---

## Task 13: Anthropic — Wire DTOs (request) + `AnthropicRequestMapper` (TDD; key: system merge, tool_use blocks)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireRequest.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapper.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapperTest.kt`

Anthropic API has structural differences vs OpenAI:
- System messages → top-level `system: String` field (NOT in `messages` array)
- Assistant tool_use → content block `{type:"tool_use", id, name, input: <parsed object>}`
- Tool results → user message with `{type:"tool_result", tool_use_id, content}` content block (NOT a separate "tool" role)
- `max_tokens` is **required** (unlike OpenAI where it's optional)

- [ ] **Step 1: Wire DTOs (request)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireRequest.kt
package neton.ai.adapter.anthropic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int,            // REQUIRED per Anthropic API
    val system: String? = null,                              // merged from AiRole.System messages
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val tools: List<AnthropicToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
    val stream: Boolean? = null,
)

@Serializable
internal data class AnthropicMessage(
    val role: String,                                        // "user" | "assistant"
    val content: List<AnthropicContentBlock>,
)

@Serializable(with = AnthropicContentBlockSerializer::class)
internal sealed interface AnthropicContentBlock {
    val type: String
    @Serializable @SerialName("text") data class Text(val text: String) : AnthropicContentBlock {
        override val type: String get() = "text"
    }
    @Serializable @SerialName("tool_use") data class ToolUse(
        val id: String, val name: String, val input: JsonElement,
    ) : AnthropicContentBlock {
        override val type: String get() = "tool_use"
    }
    @Serializable @SerialName("tool_result") data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean? = null,
    ) : AnthropicContentBlock {
        override val type: String get() = "tool_result"
    }
}

/** Polymorphic serializer keyed on "type" field (Anthropic wire format). */
internal object AnthropicContentBlockSerializer : kotlinx.serialization.json.JsonContentPolymorphicSerializer<AnthropicContentBlock>(
    AnthropicContentBlock::class,
) {
    override fun selectDeserializer(element: kotlinx.serialization.json.JsonElement) =
        when (val t = (element as kotlinx.serialization.json.JsonObject)["type"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }) {
            "text" -> AnthropicContentBlock.Text.serializer()
            "tool_use" -> AnthropicContentBlock.ToolUse.serializer()
            "tool_result" -> AnthropicContentBlock.ToolResult.serializer()
            else -> throw kotlinx.serialization.SerializationException("Unknown Anthropic content block type: $t")
        }
}

@Serializable
internal data class AnthropicToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
internal data class AnthropicToolChoice(
    val type: String,                                        // "auto" | "any" | "tool"
    val name: String? = null,                                // only for type="tool"
)
```

NOTE: `JsonContentPolymorphicSerializer` is the kotlinx.serialization API for runtime-discriminated polymorphism. Verify it's available in 1.10.0 (it is). Use this pattern; if it doesn't compile, fall back to a manual `KSerializer` implementation in the same file.

- [ ] **Step 2: Write failing RequestMapper tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapperTest.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiToolDefinition
import neton.ai.ToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.provider.ProviderCallRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicRequestMapperTest {

    private val mapper = AnthropicRequestMapper()

    private fun req(messages: List<AiMessage> = emptyList(),
                    tools: List<AiToolDefinition> = emptyList(),
                    toolChoice: ToolChoice = ToolChoice.Auto,
                    maxTokens: Int? = null) =
        ProviderCallRequest(messages, tools, toolChoice, null, maxTokens, null, emptyList(), emptyMap())

    @Test fun systemMessagesMergedIntoSystemField() {
        val out = mapper.toWire("claude", req(
            messages = listOf(
                AiMessage(AiRole.System, listOf(AiContent.Text("sys-a"))),
                AiMessage(AiRole.System, listOf(AiContent.Text("sys-b"))),
                AiMessage(AiRole.User, listOf(AiContent.Text("hi"))),
            ),
            maxTokens = 1024,
        ))
        assertEquals("sys-a\n\nsys-b", out.system)
        assertEquals(1, out.messages.size)
        assertEquals("user", out.messages[0].role)
    }

    @Test fun noSystemMessagesYieldsNullSystem() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 1024,
        ))
        assertNull(out.system)
    }

    @Test fun maxTokensRequiredDefaultsTo4096IfNotProvided() {
        // Anthropic requires max_tokens; mapper provides a default to avoid forcing every caller to set it
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = null,
        ))
        assertEquals(4096, out.maxTokens)
    }

    @Test fun maxTokensExplicitlyProvidedIsUsed() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 200,
        ))
        assertEquals(200, out.maxTokens)
    }

    @Test fun userMessageTextMapsToTextContentBlock() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hello")))),
            maxTokens = 1024,
        ))
        val block = out.messages.single().content.single() as AnthropicContentBlock.Text
        assertEquals("hello", block.text)
    }

    @Test fun assistantToolCallsMapToToolUseContentBlocks() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(
                role = AiRole.Assistant,
                content = listOf(AiContent.Text("Calling tool.")),
                toolCalls = listOf(AiToolCall("c1", "get_balance", """{"userId":7}""")),
            )),
            maxTokens = 1024,
        ))
        val msg = out.messages.single()
        assertEquals("assistant", msg.role)
        assertEquals(2, msg.content.size, "text + tool_use")
        val text = msg.content[0] as AnthropicContentBlock.Text
        assertEquals("Calling tool.", text.text)
        val toolUse = msg.content[1] as AnthropicContentBlock.ToolUse
        assertEquals("c1", toolUse.id)
        assertEquals("get_balance", toolUse.name)
        val input = toolUse.input as JsonObject
        assertEquals(JsonPrimitive(7L), input["userId"])
    }

    @Test fun toolRoleMessageMapsToUserToolResultBlock() {
        val out = mapper.toWire("claude", req(
            messages = listOf(AiMessage(
                role = AiRole.Tool,
                content = listOf(AiContent.Text("""{"balance":42}""")),
                toolCallId = "c1",
            )),
            maxTokens = 1024,
        ))
        val msg = out.messages.single()
        assertEquals("user", msg.role, "Anthropic uses user role for tool_result")
        val block = msg.content.single() as AnthropicContentBlock.ToolResult
        assertEquals("c1", block.toolUseId)
        assertEquals("""{"balance":42}""", block.content)
    }

    @Test fun toolDefinitionsMapToToolsField() {
        val out = mapper.toWire("claude", req(
            tools = listOf(AiToolDefinition(
                name = "get_balance",
                description = "Get balance",
                inputSchemaJson = """{"type":"object","properties":{"userId":{"type":"integer"}}}""",
            )),
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("hi")))),
            maxTokens = 1024,
        ))
        assertEquals(1, out.tools?.size)
        val tool = out.tools!!.single()
        assertEquals("get_balance", tool.name)
        assertEquals("Get balance", tool.description)
        // input_schema is wire field name (not "parameters")
    }

    @Test fun toolChoiceAutoMapsToAutoType() {
        val out = mapper.toWire("c", req(messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))), maxTokens = 100, toolChoice = ToolChoice.Auto))
        // tool_choice is omitted when Auto with no tools; or {type:"auto"} when tools present
        // For "no tools + Auto": null toolChoice is acceptable
        assertTrue(out.toolChoice == null || out.toolChoice!!.type == "auto")
    }

    @Test fun toolChoiceRequiredMapsToAnyType() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("t", "", "{}")),
            toolChoice = ToolChoice.Required,
        ))
        assertEquals("any", out.toolChoice?.type)
    }

    @Test fun toolChoiceNamedMapsToToolTypeWithName() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("my_tool", "", "{}")),
            toolChoice = ToolChoice.Named("my_tool"),
        ))
        assertEquals("tool", out.toolChoice?.type)
        assertEquals("my_tool", out.toolChoice?.name)
    }

    @Test fun toolChoiceNoneOmitsToolsFromRequest() {
        val out = mapper.toWire("c", req(
            messages = listOf(AiMessage(AiRole.User, listOf(AiContent.Text("x")))),
            maxTokens = 100,
            tools = listOf(AiToolDefinition("t", "", "{}")),
            toolChoice = ToolChoice.None,
        ))
        assertTrue(out.tools == null || out.tools!!.isEmpty(), "ToolChoice.None must drop tools from request")
    }
}
```

- [ ] **Step 3: Run tests (expect failure)** then **Step 4: Implement `AnthropicRequestMapper.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapper.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import neton.ai.AiContent
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.ToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.adapter.anthropic.dto.AnthropicMessage
import neton.ai.adapter.anthropic.dto.AnthropicMessagesRequest
import neton.ai.adapter.anthropic.dto.AnthropicToolChoice
import neton.ai.adapter.anthropic.dto.AnthropicToolDef
import neton.ai.provider.ProviderCallRequest

internal class AnthropicRequestMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) {
    /** Anthropic requires max_tokens; default to this if caller omits. */
    private val defaultMaxTokens = 4096

    fun toWire(modelName: String, req: ProviderCallRequest): AnthropicMessagesRequest {
        // Pull out system messages (Anthropic has top-level `system: String`, NOT a role in messages)
        val systemParts = req.messages
            .filter { it.role == AiRole.System }
            .flatMap { it.content }
            .filterIsInstance<AiContent.Text>()
            .map { it.text }
        val system = if (systemParts.isEmpty()) null else systemParts.joinToString("\n\n")

        // Remaining messages: user/assistant/tool → assistant or user (tool becomes user-with-tool_result)
        val nonSystem = req.messages.filter { it.role != AiRole.System }
        val anthropicMessages = nonSystem.map(::messageToWire)

        val toolsActive = req.toolChoice !is ToolChoice.None
        val tools = if (toolsActive && req.tools.isNotEmpty()) req.tools.map { def ->
            AnthropicToolDef(
                name = def.name,
                description = def.description,
                inputSchema = json.parseToJsonElement(def.inputSchemaJson),
            )
        } else null

        val toolChoice = if (toolsActive && tools != null) toolChoiceToWire(req.toolChoice) else null

        return AnthropicMessagesRequest(
            model = modelName,
            messages = anthropicMessages,
            maxTokens = req.maxTokens ?: defaultMaxTokens,
            system = system,
            temperature = req.temperature,
            topP = req.topP,
            stopSequences = req.stopSequences.takeIf { it.isNotEmpty() },
            tools = tools,
            toolChoice = toolChoice,
            stream = false,
        )
    }

    private fun messageToWire(m: AiMessage): AnthropicMessage = when (m.role) {
        AiRole.User -> AnthropicMessage(role = "user", content = textBlocks(m))
        AiRole.Assistant -> AnthropicMessage(
            role = "assistant",
            content = textBlocks(m) + toolUseBlocks(m),
        )
        AiRole.Tool -> AnthropicMessage(
            role = "user",
            content = listOf(AnthropicContentBlock.ToolResult(
                toolUseId = m.toolCallId ?: error("Tool message must have toolCallId"),
                content = m.content.filterIsInstance<AiContent.Text>().joinToString("\n") { it.text },
                isError = null,
            )),
        )
        AiRole.System -> error("System messages must be merged into top-level 'system' field, not mapped per-message")
    }

    private fun textBlocks(m: AiMessage): List<AnthropicContentBlock.Text> =
        m.content.filterIsInstance<AiContent.Text>().map { AnthropicContentBlock.Text(it.text) }

    private fun toolUseBlocks(m: AiMessage): List<AnthropicContentBlock.ToolUse> =
        m.toolCalls.map { tc ->
            AnthropicContentBlock.ToolUse(
                id = tc.id,
                name = tc.name,
                input = json.parseToJsonElement(tc.argumentsJson),
            )
        }

    private fun toolChoiceToWire(c: ToolChoice): AnthropicToolChoice? = when (c) {
        ToolChoice.Auto -> AnthropicToolChoice(type = "auto")
        ToolChoice.None -> null  // never reached — handled above
        ToolChoice.Required -> AnthropicToolChoice(type = "any")
        is ToolChoice.Named -> AnthropicToolChoice(type = "tool", name = c.name)
    }
}
```

- [ ] **Step 5: Run tests (12 tests expected)**, then **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireRequest.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapper.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicRequestMapperTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add Anthropic wire request DTOs + RequestMapper (system merge, tool_use blocks, tool_result-as-user) with 12 tests"
```

---

## Task 14: Anthropic — Response DTOs + `AnthropicResponseMapper` + `AnthropicTextModel` + `AnthropicProvider` + MockEngine integration

(Pattern mirrors Task 12 OpenAi-compat. Key differences: response has `content: List<content_block>`, must FLATTEN text blocks → text, tool_use blocks → AiToolCall; `stop_reason` maps differently; `usage.input_tokens`/`output_tokens` at top level.)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireResponse.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicResponseMapper.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt`
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicProvider.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicResponseMapperTest.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/AnthropicIntegrationTest.kt`

- [ ] **Step 1: Response wire DTOs**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/dto/AnthropicWireResponse.kt
package neton.ai.adapter.anthropic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val role: String = "assistant",
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

/** Error envelope per Anthropic API. */
@Serializable
internal data class AnthropicErrorEnvelope(
    val type: String = "error",
    val error: AnthropicErrorBody,
)

@Serializable
internal data class AnthropicErrorBody(
    val type: String,        // "invalid_request_error" | "authentication_error" | "rate_limit_error" | "overloaded_error" | ...
    val message: String,
)
```

- [ ] **Step 2: ResponseMapper tests + implementation**

ResponseMapper test outline (write failing test first, then implement). Tests must cover:
1. Single text block → `text` field + assistant message with one AiContent.Text
2. Multiple text blocks → concatenated with newlines into `text` field
3. Single tool_use block + no text → toolCalls populated, text empty, finishReason=ToolCalls
4. Interleaved text + tool_use → text from text blocks, toolCalls from tool_use blocks (interleaving order lost — acceptable per spec)
5. stop_reason="end_turn" → AiFinishReason.Stop
6. stop_reason="max_tokens" → AiFinishReason.Length
7. stop_reason="tool_use" → AiFinishReason.ToolCalls
8. stop_reason="stop_sequence" → AiFinishReason.Stop
9. stop_reason=null/unknown → AiFinishReason.Other
10. usage.input_tokens + output_tokens → AiUsage with totalTokens=null (Anthropic doesn't provide total)
11. usage absent → null
12. Error mapping: 401 → Unauthorized; 403 → Forbidden; 429 → RateLimited; 5xx → ServerError; 400 type=invalid_request_error with "context length" → ContextLengthExceeded; 400 other → InvalidRequest

Implementation:

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicResponseMapper.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import neton.ai.AiContent
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.AiFinishReason
import neton.ai.AiMessage
import neton.ai.AiRole
import neton.ai.AiToolCall
import neton.ai.AiUsage
import neton.ai.adapter.anthropic.dto.AnthropicContentBlock
import neton.ai.adapter.anthropic.dto.AnthropicErrorEnvelope
import neton.ai.adapter.anthropic.dto.AnthropicMessagesResponse
import neton.ai.provider.ProviderCallResponse

internal class AnthropicResponseMapper(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    fun fromWireBody(body: String): ProviderCallResponse {
        val resp = try {
            json.decodeFromString(AnthropicMessagesResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw AiException(AiError.Unknown("Invalid Anthropic response JSON: ${e.message}", e))
        }
        val texts = resp.content.filterIsInstance<AnthropicContentBlock.Text>().map { it.text }
        val toolUses = resp.content.filterIsInstance<AnthropicContentBlock.ToolUse>()
        val text = texts.joinToString("\n")
        val toolCalls = toolUses.map { tu ->
            AiToolCall(
                id = tu.id,
                name = tu.name,
                argumentsJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), tu.input),
            )
        }
        return ProviderCallResponse(
            message = AiMessage(
                role = AiRole.Assistant,
                content = if (text.isNotEmpty()) listOf(AiContent.Text(text)) else emptyList(),
                toolCalls = toolCalls,
            ),
            text = text,
            toolCalls = toolCalls,
            usage = resp.usage?.let { AiUsage(inputTokens = it.inputTokens, outputTokens = it.outputTokens, totalTokens = null) },
            finishReason = mapStopReason(resp.stopReason),
        )
    }

    fun errorFromStatus(statusCode: Int, body: String): Nothing {
        val parsed = tryParseError(body)
        val msg = parsed?.message ?: body.take(500)
        val type = parsed?.type
        throw AiException(when (statusCode) {
            401 -> AiError.Unauthorized(msg)
            403 -> AiError.Forbidden(msg)
            429 -> AiError.RateLimited(retryAfterMillis = null, message = msg)
            in 500..599 -> AiError.ServerError(statusCode, msg)
            400 -> when {
                type == "invalid_request_error" && msg.contains("context", ignoreCase = true) ->
                    AiError.ContextLengthExceeded(msg)
                else -> AiError.InvalidRequest(msg)
            }
            404 -> AiError.ModelNotFound("unknown", msg)
            else -> AiError.Unknown("HTTP $statusCode: $msg", null)
        })
    }

    private fun mapStopReason(s: String?): AiFinishReason = when (s) {
        "end_turn" -> AiFinishReason.Stop
        "max_tokens" -> AiFinishReason.Length
        "tool_use" -> AiFinishReason.ToolCalls
        "stop_sequence" -> AiFinishReason.Stop
        else -> AiFinishReason.Other
    }

    private fun tryParseError(body: String) = try {
        json.decodeFromString(AnthropicErrorEnvelope.serializer(), body).error
    } catch (_: Throwable) { null }
}
```

- [ ] **Step 3: `AnthropicTextModel.kt` + `AnthropicProvider.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt
package neton.ai.adapter.anthropic

import kotlinx.serialization.json.Json
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.adapter.anthropic.dto.AnthropicMessagesRequest
import neton.ai.provider.AiTextModel
import neton.ai.provider.ProviderCallRequest
import neton.ai.provider.ProviderCallResponse
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest

internal class AnthropicTextModel(
    override val providerId: String,
    override val modelName: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val version: String,
    private val beta: List<String>,
    private val defaultHeaders: Map<String, String>,
    private val requestMapper: AnthropicRequestMapper = AnthropicRequestMapper(),
    private val responseMapper: AnthropicResponseMapper = AnthropicResponseMapper(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
) : AiTextModel {
    override suspend fun generate(request: ProviderCallRequest): ProviderCallResponse {
        val wire = requestMapper.toWire(modelName, request)
        val bodyJson = json.encodeToString(AnthropicMessagesRequest.serializer(), wire)
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", version)
            if (beta.isNotEmpty()) put("anthropic-beta", beta.joinToString(","))
            putAll(defaultHeaders)
        }
        val resp = try {
            httpClient.request(NetonHttpRequest(
                method = NetonHttpMethod.Post,
                url = "$baseUrl/v1/messages",
                headers = headers,
                body = NetonHttpBody.Json(bodyJson),
                metadata = request.metadata,
            ))
        } catch (e: NetonHttpException) {
            throw AiException(when (val err = e.error) {
                is neton.http.client.NetonHttpError.Network -> AiError.Network(err.message, err.cause)
                is neton.http.client.NetonHttpError.Timeout -> AiError.Timeout(err.message, err.cause)
                is neton.http.client.NetonHttpError.Http -> throw IllegalStateException("Http error path unused")
                is neton.http.client.NetonHttpError.Unknown -> AiError.Unknown(err.message, err.cause)
            })
        }
        if (resp.statusCode !in 200..299) {
            responseMapper.errorFromStatus(resp.statusCode, resp.body)
        }
        return responseMapper.fromWireBody(resp.body)
    }
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicProvider.kt
package neton.ai.adapter.anthropic

import neton.ai.provider.AiEmbeddingModel
import neton.ai.provider.AiProvider
import neton.ai.provider.AiStreamingTextModel
import neton.ai.provider.AiTextModel
import neton.http.client.NetonHttpClient

class AnthropicProvider(
    override val id: String,
    private val httpClient: NetonHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val version: String = "2023-06-01",
    private val beta: List<String> = emptyList(),
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : AiProvider {
    override fun textModel(modelName: String): AiTextModel = AnthropicTextModel(
        providerId = id, modelName = modelName, httpClient = httpClient,
        baseUrl = baseUrl, apiKey = apiKey, version = version, beta = beta,
        defaultHeaders = defaultHeaders,
    )
    override fun streamingTextModel(modelName: String): AiStreamingTextModel? = null   // PR2
    override fun embeddingModel(modelName: String): AiEmbeddingModel? = null            // Anthropic has no embeddings
}
```

- [ ] **Step 4: Integration test** (mirror Task 12's pattern; covers: non-stream chat, tool_use response, 401 error mapping, max_tokens default, system message merging end-to-end)

- [ ] **Step 5: Run all Anthropic tests (mapper ~12 + integration ~3 = ~15 tests)** then **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/ \
    neton-ai/src/commonTest/kotlin/neton/ai/adapter/anthropic/
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add Anthropic adapter (Response DTOs/Mapper + TextModel + Provider) + ResponseMapper tests + MockEngine integration"
```

---

## Task 15: `AiConfig` DSL + `ProviderSpec` hierarchy + builders

**Files (all in `src/commonMain/kotlin/neton/ai/config/` + `src/commonMain/kotlin/neton/ai/AiConfig.kt`):**

- [ ] **Step 1: `ProviderSpec.kt` sealed interface**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/ProviderSpec.kt
package neton.ai.config

sealed interface ProviderSpec { val id: String }
```

- [ ] **Step 2: `OpenAiCompatibleSpec.kt` + `AnthropicSpec.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/OpenAiCompatibleSpec.kt
package neton.ai.config

/**
 * All fields nullable to distinguish "not set in DSL" from "set to default value", per spec §4.2.
 * Effective config applies defaults after file merge (see Task 16).
 */
class OpenAiCompatibleSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null
    var apiKey: String? = null
    var organization: String? = null
    var timeoutMillis: Long? = null
    var defaultHeaders: Map<String, String>? = null
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/AnthropicSpec.kt
package neton.ai.config

class AnthropicSpec(override val id: String) : ProviderSpec {
    var baseUrl: String? = null
    var apiKey: String? = null
    var version: String? = null
    var beta: List<String>? = null
    var timeoutMillis: Long? = null
    var defaultHeaders: Map<String, String>? = null
}
```

- [ ] **Step 3: Builders (`ProvidersBuilder` / `RoutingBuilder` / `PolicyBuilder` / `UsageBuilder` / `UsageConfig`)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/ProvidersBuilder.kt
package neton.ai.config

class ProvidersBuilder internal constructor(internal val target: MutableMap<String, ProviderSpec>) {
    private val idPattern = Regex("[a-zA-Z0-9._-]+")

    fun openAiCompatible(id: String, block: OpenAiCompatibleSpec.() -> Unit) {
        require(idPattern.matches(id)) { "Invalid provider id '$id' (allowed: [a-zA-Z0-9._-]+)" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = OpenAiCompatibleSpec(id).apply(block)
    }

    fun anthropic(id: String, block: AnthropicSpec.() -> Unit) {
        require(idPattern.matches(id)) { "Invalid provider id '$id'" }
        require(id !in target) { "Duplicate provider id '$id'" }
        target[id] = AnthropicSpec(id).apply(block)
    }
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/PolicyBuilder.kt
package neton.ai.config

import neton.ai.AiModelId
import neton.ai.routing.ModelPolicy

class PolicyBuilder internal constructor() {
    private val prefer = mutableListOf<AiModelId>()
    private val fallback = mutableListOf<AiModelId>()
    fun prefer(modelId: String) { prefer += AiModelId.parse(modelId) }
    fun fallback(modelId: String) { fallback += AiModelId.parse(modelId) }
    internal fun build() = ModelPolicy(prefer.toList(), fallback.toList())
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/RoutingBuilder.kt
package neton.ai.config

import neton.ai.AiModelId
import neton.ai.routing.ModelPolicy
import neton.ai.routing.RoutingConfig

class RoutingBuilder internal constructor() {
    var defaultModel: String? = null
    private val policies = mutableMapOf<String, ModelPolicy>()

    fun policy(name: String, block: PolicyBuilder.() -> Unit) {
        require(name.isNotBlank()) { "Policy name must not be blank" }
        require(name !in policies) { "Duplicate policy '$name'" }
        policies[name] = PolicyBuilder().apply(block).build()
    }

    internal fun build(): RoutingConfig = RoutingConfig(
        defaultModel = defaultModel?.let(AiModelId::parse),
        policies = policies.toMap(),
    )
}
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/UsageConfig.kt
package neton.ai.config

import neton.ai.usage.AiUsageRecorder

data class UsageConfig(val recorder: AiUsageRecorder?)
```

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/config/UsageBuilder.kt
package neton.ai.config

import neton.ai.usage.AiUsageRecorder

class UsageBuilder internal constructor() {
    var recorder: AiUsageRecorder? = null
    internal fun build() = UsageConfig(recorder)
}
```

- [ ] **Step 4: `AiConfig.kt` (root DSL)**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiConfig.kt
package neton.ai

import neton.ai.config.ProviderSpec
import neton.ai.config.ProvidersBuilder
import neton.ai.config.RoutingBuilder
import neton.ai.config.UsageBuilder
import neton.ai.config.UsageConfig
import neton.ai.routing.RoutingConfig

/**
 * Root DSL config. Used by both standalone (AiClient.Companion.create) and component (AiComponent).
 *
 * `httpClient` field: required for standalone usage; component usage gets it from NetonContext
 * and sets this field internally before the DSL block runs.
 */
class AiConfig {
    var httpClient: neton.http.client.NetonHttpClient? = null
    internal val providers = mutableMapOf<String, ProviderSpec>()
    internal var routing: RoutingConfig = RoutingConfig()
    internal var usage: UsageConfig = UsageConfig(recorder = null)
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

    /** Validation per spec §3.9. Returns list of error strings; empty = valid. */
    internal fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (httpClient == null) errors += "httpClient is required"
        if (providers.isEmpty()) errors += "at least one provider must be configured"
        for ((id, spec) in providers) {
            when (spec) {
                is neton.ai.config.OpenAiCompatibleSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                    val url = spec.baseUrl
                    if (url.isNullOrBlank()) errors += "provider '$id': baseUrl is required"
                    else if (!url.startsWith("http://") && !url.startsWith("https://"))
                        errors += "provider '$id': baseUrl must start with http:// or https://"
                }
                is neton.ai.config.AnthropicSpec -> {
                    if (spec.apiKey.isNullOrBlank()) errors += "provider '$id': apiKey is blank"
                }
            }
        }
        routing.defaultModel?.let { def ->
            if (def.providerId !in providers) errors += "routing.defaultModel references unknown provider '${def.providerId}'"
        }
        for ((name, policy) in routing.policies) {
            if (policy.prefer.isEmpty()) errors += "policy '$name': prefer list is empty"
            for (m in policy.prefer + policy.fallback) {
                if (m.providerId !in providers) errors += "policy '$name': references unknown provider '${m.providerId}'"
            }
        }
        return errors
    }
}
```

- [ ] **Step 5: Verify compilation**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:compileKotlinMacosArm64 2>&1 | tail -5`

- [ ] **Step 6: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiConfig.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/config/
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiConfig DSL + ProviderSpec hierarchy + builders"
```

---

## Task 16: `AiConfig.fromMap` (TOML config file → DSL) + merge logic (TDD)

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/internal/AiConfigMerge.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/config/AiConfigMergeTest.kt`

Per spec §4.3: `config/ai.conf` is TOML, fields camelCase. Merge precedence: **file → DSL explicit override → built-in defaults**. Same provider id in both DSL and file → field-level merge.

Neton's `ConfigLoader.loadModuleConfig("ai", ...)` returns `Map<String, Any?>`. We parse this map into `AiConfig` (or merge into an existing one).

- [ ] **Step 1: Write failing merge tests**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/config/AiConfigMergeTest.kt
package neton.ai.config

import neton.ai.AiConfig
import neton.ai.AiModelId
import neton.ai.internal.applyFileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiConfigMergeTest {

    // Sample TOML-decoded map (what ConfigLoader produces for ai.conf)
    private fun fileMap() = mapOf<String, Any?>(
        "debug" to false,
        "providers" to mapOf<String, Any?>(
            "openai" to mapOf<String, Any?>(
                "type" to "openAiCompatible",
                "baseUrl" to "https://api.openai.com/v1",
                "apiKey" to "sk-from-file",
                "timeoutMillis" to 30_000L,
            ),
            "anthropic" to mapOf<String, Any?>(
                "type" to "anthropic",
                "apiKey" to "ak-from-file",
                "version" to "2023-06-01",
            ),
        ),
        "routing" to mapOf<String, Any?>(
            "defaultModel" to "openai:gpt-4o-mini",
            "policies" to mapOf<String, Any?>(
                "strong" to mapOf<String, Any?>(
                    "prefer" to listOf("anthropic:claude-sonnet-4.5"),
                    "fallback" to listOf("openai:gpt-4o"),
                ),
            ),
        ),
    )

    @Test fun fileOnlyMergeBuildsCompleteConfig() {
        val cfg = AiConfig().apply { applyFileMap(fileMap()) }
        assertEquals(2, cfg.providers.size)
        val openai = cfg.providers["openai"] as OpenAiCompatibleSpec
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals("sk-from-file", openai.apiKey)
        assertEquals(30_000L, openai.timeoutMillis)
        val anthropic = cfg.providers["anthropic"] as AnthropicSpec
        assertEquals("ak-from-file", anthropic.apiKey)
        assertEquals("2023-06-01", anthropic.version)
        assertEquals(AiModelId("openai", "gpt-4o-mini"), cfg.routing.defaultModel)
        assertEquals(1, cfg.routing.policies.size)
    }

    @Test fun dslExplicitOverridesFileField() {
        // DSL sets timeoutMillis on openai BEFORE merge applies file values
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("openai") {
                    timeoutMillis = 120_000L  // DSL explicit
                    // baseUrl, apiKey unset — should come from file
                }
            }
            applyFileMap(fileMap())
        }
        val openai = cfg.providers["openai"] as OpenAiCompatibleSpec
        assertEquals(120_000L, openai.timeoutMillis, "DSL value preserved")
        assertEquals("https://api.openai.com/v1", openai.baseUrl, "file fills unset DSL field")
        assertEquals("sk-from-file", openai.apiKey)
    }

    @Test fun newProviderInFileMergedAlongsideDslProviders() {
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("deepseek") {
                    baseUrl = "https://api.deepseek.com"
                    apiKey = "ds-key"
                }
            }
            applyFileMap(fileMap())
        }
        assertEquals(3, cfg.providers.size, "DSL deepseek + file openai + file anthropic")
        assertTrue("deepseek" in cfg.providers)
        assertTrue("openai" in cfg.providers)
        assertTrue("anthropic" in cfg.providers)
    }

    @Test fun emptyMapDoesNotChangeDsl() {
        val cfg = AiConfig().apply {
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://x"
                    apiKey = "k"
                }
            }
            applyFileMap(emptyMap())
        }
        assertEquals(1, cfg.providers.size)
    }

    @Test fun fileWithUnknownProviderTypeIsSkippedWithErrorOnValidate() {
        val cfg = AiConfig().apply {
            applyFileMap(mapOf(
                "providers" to mapOf(
                    "weird" to mapOf("type" to "unknown_type", "apiKey" to "x"),
                ),
            ))
        }
        // applyFileMap silently skips unknown types (no exception); validation catches it
        assertTrue(cfg.providers.isEmpty(), "unknown provider type is not added")
    }
}
```

- [ ] **Step 2: Run tests (expect failure)** then **Step 3: Implement `AiConfigMerge.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/AiConfigMerge.kt
package neton.ai.internal

import neton.ai.AiConfig
import neton.ai.AiModelId
import neton.ai.config.AnthropicSpec
import neton.ai.config.OpenAiCompatibleSpec
import neton.ai.config.ProviderSpec
import neton.ai.routing.ModelPolicy
import neton.ai.routing.RoutingConfig

/**
 * Merge a file-config map (from neton-core ConfigLoader) into an AiConfig that may already have
 * DSL-set fields. DSL takes precedence per spec §4.3.
 *
 * Schema:
 *   debug = bool
 *   [providers.<id>]
 *     type = "openAiCompatible" | "anthropic"
 *     baseUrl, apiKey, timeoutMillis, organization (openAiCompatible)
 *     baseUrl, apiKey, timeoutMillis, version, beta (anthropic)
 *   [routing]
 *     defaultModel = "provider:model"
 *   [routing.policies.<name>]
 *     prefer = ["provider:model", ...]
 *     fallback = ["provider:model", ...]
 */
internal fun AiConfig.applyFileMap(map: Map<String, Any?>) {
    (map["debug"] as? Boolean)?.let { /* file 'debug' only sets DSL.debug if DSL.debug is false (default) */
        if (!debug) debug = it
    }

    @Suppress("UNCHECKED_CAST")
    val fileProviders = map["providers"] as? Map<String, Any?> ?: emptyMap()
    for ((id, raw) in fileProviders) {
        @Suppress("UNCHECKED_CAST")
        val pm = raw as? Map<String, Any?> ?: continue
        val type = pm["type"] as? String ?: continue
        val existing = providers[id]
        when (type) {
            "openAiCompatible" -> {
                val spec = (existing as? OpenAiCompatibleSpec) ?: OpenAiCompatibleSpec(id).also { providers[id] = it }
                mergeOpenAiCompatible(spec, pm)
            }
            "anthropic" -> {
                val spec = (existing as? AnthropicSpec) ?: AnthropicSpec(id).also { providers[id] = it }
                mergeAnthropic(spec, pm)
            }
            else -> { /* skip unknown type; validate() will surface as missing config */ }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val fileRouting = map["routing"] as? Map<String, Any?>
    if (fileRouting != null) {
        // Build a merged RoutingConfig: file values fill unset DSL fields
        val defaultModelFromFile = (fileRouting["defaultModel"] as? String)?.let(AiModelId::parse)
        val mergedDefault = routing.defaultModel ?: defaultModelFromFile

        @Suppress("UNCHECKED_CAST")
        val filePolicies = fileRouting["policies"] as? Map<String, Any?> ?: emptyMap()
        val mergedPolicies = routing.policies.toMutableMap()  // DSL policies preserved
        for ((name, raw) in filePolicies) {
            if (name in mergedPolicies) continue  // DSL wins for same policy name
            @Suppress("UNCHECKED_CAST")
            val pm = raw as? Map<String, Any?> ?: continue
            @Suppress("UNCHECKED_CAST")
            val prefer = (pm["prefer"] as? List<String>).orEmpty().map(AiModelId::parse)
            @Suppress("UNCHECKED_CAST")
            val fallback = (pm["fallback"] as? List<String>).orEmpty().map(AiModelId::parse)
            mergedPolicies[name] = ModelPolicy(prefer, fallback)
        }
        routing = RoutingConfig(defaultModel = mergedDefault, policies = mergedPolicies)
    }
}

private fun mergeOpenAiCompatible(spec: OpenAiCompatibleSpec, m: Map<String, Any?>) {
    if (spec.baseUrl == null) (m["baseUrl"] as? String)?.let { spec.baseUrl = it }
    if (spec.apiKey == null) (m["apiKey"] as? String)?.let { spec.apiKey = it }
    if (spec.organization == null) (m["organization"] as? String)?.let { spec.organization = it }
    if (spec.timeoutMillis == null) (m["timeoutMillis"] as? Number)?.toLong()?.let { spec.timeoutMillis = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.defaultHeaders == null) (m["defaultHeaders"] as? Map<String, String>)?.let { spec.defaultHeaders = it }
}

private fun mergeAnthropic(spec: AnthropicSpec, m: Map<String, Any?>) {
    if (spec.baseUrl == null) (m["baseUrl"] as? String)?.let { spec.baseUrl = it }
    if (spec.apiKey == null) (m["apiKey"] as? String)?.let { spec.apiKey = it }
    if (spec.version == null) (m["version"] as? String)?.let { spec.version = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.beta == null) (m["beta"] as? List<String>)?.let { spec.beta = it }
    if (spec.timeoutMillis == null) (m["timeoutMillis"] as? Number)?.toLong()?.let { spec.timeoutMillis = it }
    @Suppress("UNCHECKED_CAST")
    if (spec.defaultHeaders == null) (m["defaultHeaders"] as? Map<String, String>)?.let { spec.defaultHeaders = it }
}

/**
 * Resolve effective provider config — apply built-in defaults to any still-null fields.
 * Called by AiClientFactory after applyFileMap; returns a value-class snapshot used to construct providers.
 */
internal data class EffectiveOpenAiCompatibleConfig(
    val id: String, val baseUrl: String, val apiKey: String,
    val organization: String?, val timeoutMillis: Long, val defaultHeaders: Map<String, String>,
)

internal data class EffectiveAnthropicConfig(
    val id: String, val baseUrl: String, val apiKey: String, val version: String,
    val beta: List<String>, val timeoutMillis: Long, val defaultHeaders: Map<String, String>,
)

internal fun OpenAiCompatibleSpec.toEffective(): EffectiveOpenAiCompatibleConfig =
    EffectiveOpenAiCompatibleConfig(
        id = id,
        baseUrl = baseUrl ?: error("OpenAiCompat '$id': baseUrl missing (should have been caught by validate())"),
        apiKey = apiKey ?: error("OpenAiCompat '$id': apiKey missing"),
        organization = organization,
        timeoutMillis = timeoutMillis ?: 60_000L,
        defaultHeaders = defaultHeaders ?: emptyMap(),
    )

internal fun AnthropicSpec.toEffective(): EffectiveAnthropicConfig =
    EffectiveAnthropicConfig(
        id = id,
        baseUrl = baseUrl ?: "https://api.anthropic.com",
        apiKey = apiKey ?: error("Anthropic '$id': apiKey missing"),
        version = version ?: "2023-06-01",
        beta = beta ?: emptyList(),
        timeoutMillis = timeoutMillis ?: 60_000L,
        defaultHeaders = defaultHeaders ?: emptyMap(),
    )
```

- [ ] **Step 4: Run tests** then **Step 5: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/internal/AiConfigMerge.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/config/AiConfigMergeTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiConfig.applyFileMap (TOML map merge) + EffectiveConfig snapshots with 5 tests"
```

---

## Task 17: `AiClientFactory.createFromConfig` + `AiClient.Companion.create` standalone factory

Now we have `AiConfig` (Task 15) + merge (Task 16). Wire up:
- `AiClientFactory.createFromConfig(config: AiConfig): AiClient` — builds registry from provider specs, creates router, creates DefaultAiClient
- `AiClient.Companion.create(block)` — the public standalone Mode 1 entry point

**Files:**
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt` (add Companion.create body)
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt` (add createFromConfig)

- [ ] **Step 1: Extend `AiClientFactory.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt
package neton.ai.internal

import neton.ai.AiClient
import neton.ai.AiConfig
import neton.ai.AiError
import neton.ai.AiException
import neton.ai.adapter.anthropic.AnthropicProvider
import neton.ai.adapter.openaicompatible.OpenAiCompatibleProvider
import neton.ai.config.AnthropicSpec
import neton.ai.config.OpenAiCompatibleSpec
import neton.ai.provider.AiProvider
import neton.ai.provider.ProviderRegistry
import neton.ai.routing.ModelRouter
import neton.ai.usage.AiUsageRecorder
import neton.ai.usage.NoopAiUsageRecorder

internal object AiClientFactory {
    /** Low-level: construct from already-resolved dependencies. */
    fun create(
        registry: ProviderRegistry,
        router: ModelRouter,
        recorder: AiUsageRecorder,
    ): AiClient = DefaultAiClient(registry, router, recorder)

    /**
     * High-level: construct from an AiConfig that has already been merged with file values.
     * Validates the config; throws AiException(InvalidRequest) on errors.
     */
    fun createFromConfig(config: AiConfig): AiClient {
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            throw AiException(AiError.InvalidRequest("Invalid AI config: ${errors.joinToString()}"))
        }
        val httpClient = config.httpClient ?: error("validate() should have caught null httpClient")

        val providers: Map<String, AiProvider> = config.providers.mapValues { (_, spec) ->
            when (spec) {
                is OpenAiCompatibleSpec -> {
                    val eff = spec.toEffective()
                    OpenAiCompatibleProvider(
                        id = eff.id, httpClient = httpClient,
                        baseUrl = eff.baseUrl, apiKey = eff.apiKey,
                        organization = eff.organization, defaultHeaders = eff.defaultHeaders,
                    )
                }
                is AnthropicSpec -> {
                    val eff = spec.toEffective()
                    AnthropicProvider(
                        id = eff.id, httpClient = httpClient,
                        baseUrl = eff.baseUrl, apiKey = eff.apiKey,
                        version = eff.version, beta = eff.beta,
                        defaultHeaders = eff.defaultHeaders,
                    )
                }
            }
        }

        return create(
            registry = DefaultProviderRegistry(providers),
            router = DefaultModelRouter(config.routing),
            recorder = config.usage.recorder ?: NoopAiUsageRecorder,
        )
    }
}
```

- [ ] **Step 2: Add `Companion.create` to `AiClient.kt`**

Replace the empty `companion object` in `AiClient.kt`:

```kotlin
    companion object {
        /**
         * Standalone factory (Mode 1). Constructs an AiClient from a DSL block WITHOUT requiring
         * any Neton runtime (Neton.run / NetonContext). Caller must provide an NetonHttpClient.
         *
         * Example:
         *   val ai = AiClient.create {
         *       httpClient = NetonHttpClient.create { requestMillis = 30_000 }
         *       providers {
         *           openAiCompatible("openai") { baseUrl = "..."; apiKey = "sk-..." }
         *       }
         *       routing { defaultModel = "openai:gpt-4o-mini" }
         *   }
         *
         * @throws AiException(InvalidRequest) on invalid config.
         */
        fun create(block: AiConfig.() -> Unit): AiClient {
            val cfg = AiConfig().apply(block)
            return neton.ai.internal.AiClientFactory.createFromConfig(cfg)
        }
    }
```

- [ ] **Step 3: Verify compilation + run all existing tests**

`./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test 2>&1 | tail -10`

Expected: all prior tests still pass.

- [ ] **Step 4: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiClient.Companion.create standalone factory + AiClientFactory.createFromConfig"
```

---

## Task 18: `AiComponent` (Mode 2 thin adapter) + `Neton.LaunchBuilder.ai` DSL + standalone test + component boot test

**Files:**
- Create: `neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt`
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/AiComponentBootTest.kt`

- [ ] **Step 1: `AiComponent.kt`**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt
package neton.ai

import neton.ai.internal.AiClientFactory
import neton.ai.internal.applyFileMap
import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.http.client.NetonHttpClient
import neton.logging.LoggerFactory

/**
 * Mode 2 thin adapter — binds AiClient into NetonContext for downstream code.
 *
 * Precondition: HttpClientComponent must be installed BEFORE this component. AiComponent.init
 * fail-fasts with a clear error if NetonHttpClient is not in the context.
 *
 * Config flow per spec §4.3:
 *   1. DSL block runs (caller-provided config)
 *   2. config/ai.conf is loaded and merged (DSL explicit fields preserved)
 *   3. validate() runs; bind on success
 */
object AiComponent : NetonComponent<AiConfig> {

    override fun defaultConfig(): AiConfig = AiConfig()

    override suspend fun init(ctx: NetonContext, config: AiConfig) {
        val httpClient = ctx.getOrNull(NetonHttpClient::class)
            ?: throw AiException(AiError.InvalidRequest(
                "neton-http-client must be installed before neton-ai. " +
                "Add `httpClient { ... }` before `ai { ... }` in your Neton.run { ... } block."
            ))
        // Wire httpClient from context (Mode 2 caller doesn't set it manually in DSL)
        config.httpClient = httpClient

        // Merge file config (config/ai.conf) into DSL config
        @Suppress("UNCHECKED_CAST")
        val fileMap = ConfigLoader.loadModuleConfig(
            moduleName = "ai",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args,
        ) as? Map<String, Any?>
        if (fileMap != null) config.applyFileMap(fileMap)

        // Build via single source of truth (same path standalone uses)
        val client = AiClientFactory.createFromConfig(config)
        ctx.bind(AiClient::class, client)

        if (config.debug) {
            val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.ai")
            log?.info("AI initialized via Neton component", mapOf(
                "providers" to config.providers.keys.toList(),
                "defaultModel" to config.routing.defaultModel?.toString(),
                "policies" to config.routing.policies.keys.toList(),
            ))
        }
    }
}

/** DSL entry: `ai { providers { ... }; routing { ... } }` */
fun Neton.LaunchBuilder.ai(block: AiConfig.() -> Unit) {
    install(AiComponent, block)
}
```

- [ ] **Step 2: Standalone-usage test (Mode 1 contract guardrail)**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt
//
// CONTRACT GUARDRAIL: Mode 1 dual-usage. MUST NOT import:
//   - neton.core.*
//   - neton.logging.*
//   - neton.ai.internal.*    (internal types)
//   - neton.http.client.internal.*
//
// If this test ever needs Neton runtime imports, the standalone-usage contract is broken.
package neton.ai

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiClientFactoryStandaloneTest {

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun createWithMinimalConfigSucceeds() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://api.example.com/v1"
                    apiKey = "sk-test"
                }
            }
            routing { defaultModel = "openai:gpt-4o-mini" }
        }
        val result = ai.generateText {
            user("hi")
        }
        assertEquals("ok", result.text)
        assertEquals("openai", result.providerId)
        ai.close()
    }

    @Test fun missingHttpClientFailsValidate() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "openai:m" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("httpClient" in ex.error.message)
    }

    @Test fun missingProvidersFailsValidate() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                httpClient = NetonHttpClient.create()
                routing { defaultModel = "x:y" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
    }

    @Test fun routingReferencingUnknownProviderFails() {
        val ex = assertFailsWith<AiException> {
            AiClient.create {
                httpClient = NetonHttpClient.create()
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "missing:m" }
            }
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("missing" in ex.error.message)
    }

    @Test fun dualProviderSetupBuilds() = runTest {
        val ai = AiClient.create {
            httpClient = NetonHttpClient.create()
            providers {
                openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" }
                anthropic("anthropic") { apiKey = "a" }
            }
            routing {
                defaultModel = "openai:m"
                policy("strong") {
                    prefer("anthropic:claude-sonnet-4.5")
                    fallback("openai:gpt-4o")
                }
            }
        }
        // Don't actually call; just verify the AiClient was built successfully.
        ai.close()
    }
}
```

- [ ] **Step 3: Component boot test (Mode 2)**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/AiComponentBootTest.kt
package neton.ai

import neton.core.component.NetonContext
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests AiComponent.init() behavior using a stub NetonContext.
 *
 * NOTE: this test IS allowed to import neton.core (it tests the Mode 2 component, not Mode 1).
 */
class AiComponentBootTest {

    @Test fun initFailsWithClearMessageWhenHttpClientMissing() = kotlinx.coroutines.test.runTest {
        val ctx = StubContext()  // no NetonHttpClient bound
        val ex = assertFailsWith<AiException> {
            AiComponent.init(ctx, AiConfig().apply {
                providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
                routing { defaultModel = "openai:m" }
            })
        }
        assertTrue(ex.error is AiError.InvalidRequest)
        assertTrue("neton-http-client" in ex.error.message,
            "error must direct user to install neton-http-client first; was: ${ex.error.message}")
    }

    @Test fun initBindsAiClientWhenConfigValid() = kotlinx.coroutines.test.runTest {
        val ctx = StubContext()
        val httpClient = NetonHttpClient.create()
        ctx.bind(NetonHttpClient::class, httpClient)
        AiComponent.init(ctx, AiConfig().apply {
            providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
            routing { defaultModel = "openai:m" }
        })
        val ai = ctx.getOrNull(AiClient::class)
        assertNotNull(ai, "AiClient should be bound after init")
        httpClient.close()
    }

    /** Minimal NetonContext stub for tests. Real implementation lives in neton-core. */
    private class StubContext : NetonContext {
        private val bindings = mutableMapOf<kotlin.reflect.KClass<*>, Any>()
        override val args: Array<String> = emptyArray()
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getOrNull(type: kotlin.reflect.KClass<T>): T? = bindings[type] as T?
        override fun <T : Any> get(type: kotlin.reflect.KClass<T>): T =
            getOrNull(type) ?: error("No binding for $type")
        override fun <T : Any> bind(type: kotlin.reflect.KClass<T>, instance: T) {
            bindings[type] = instance
        }
        // ... any other NetonContext methods need stub impls; check neton-core/NetonContext.kt
    }
}
```

**IMPORTANT**: the `StubContext` class must match the actual `NetonContext` interface from `neton-core`. The implementer should:
1. Read `neton-core/src/commonMain/kotlin/neton/core/component/NetonContext.kt` first
2. Adapt the stub to match its actual method signatures (the stub above is plausible but may need additions)
3. If `NetonContext` has many methods, alternative is to use the real `NetonContext` implementation from neton-core (if it has a no-args constructor) instead of a stub

- [ ] **Step 4: Run all tests + verify dual-usage contract**

```
./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test 2>&1 | tail -10
grep -E "^import (neton\.core|neton\.logging|neton\.ai\.internal|neton\.http\.client\.internal)" /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt
```

Second grep must return **0 matches**. If any → fix before commit.

- [ ] **Step 5: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/AiComponentBootTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): add AiComponent (Mode 2 thin adapter) + Neton.LaunchBuilder.ai DSL + standalone (5) + component boot (2) tests"
```

---

## Task 19: Redaction wire-up (close PR0 Gate 13) + verification test

**Files:**
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt` (pass redaction-aware logger)
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt` (same)
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt` (inject logger from config)
- Modify: `neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt` (wire LoggerFactory)
- Create: `neton-ai/src/commonTest/kotlin/neton/ai/usage/RedactionVerificationTest.kt`

**Design**: each provider TextModel takes an optional `LogSink` lambda (NOT a `neton.logging.Logger` type — that would couple standalone path to neton-logging). The sink is called when `debug=true` to emit a sanitized request summary. The sink itself MUST NOT receive API keys; the model code is responsible for stripping them BEFORE calling the sink.

- [ ] **Step 1: Add `LogSink` type alias + redaction helper in standalone-safe location**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt (add at bottom of file)
package neton.ai

/**
 * Lightweight log sink — a function that accepts a sanitized log line. Decoupled from
 * neton-logging so standalone usage (Mode 1) doesn't pull in the logging runtime.
 *
 * In Mode 2 (AiComponent), AiComponent wires this to neton-logging's Logger. In Mode 1,
 * callers can pass `::println` or any other sink, or omit entirely (no logs).
 */
typealias AiLogSink = (line: String) -> Unit
```

Add an `AiLogSink?` field to `AiConfig`:

```kotlin
// in neton-ai/src/commonMain/kotlin/neton/ai/AiConfig.kt — add field
var logSink: AiLogSink? = null
```

- [ ] **Step 2: Add redaction helper**

```kotlin
// neton-ai/src/commonMain/kotlin/neton/ai/internal/Redaction.kt (NEW file)
package neton.ai.internal

/**
 * Header keys whose VALUES MUST NEVER appear in logs (case-insensitive). Mirrors and includes
 * neton-http-client's DEFAULT_REDACTED_HEADERS for AI-specific keys.
 */
internal val REDACTED_HEADER_KEYS: Set<String> = setOf(
    "authorization", "x-api-key", "api-key", "anthropic-api-key",
    "cookie", "set-cookie", "proxy-authorization", "openai-organization",
)

internal fun Map<String, String>.withRedactedValues(): Map<String, String> =
    mapValues { (k, v) ->
        if (k.lowercase() in REDACTED_HEADER_KEYS) "<redacted>" else v
    }
```

- [ ] **Step 3: Update provider TextModels to use logSink + redaction**

In both `OpenAiCompatibleTextModel` and `AnthropicTextModel`, add an `internal val logSink: AiLogSink? = null` constructor parameter and a `debug: Boolean = false` parameter. Then in `generate()`, after constructing `headers`, if `debug && logSink != null` emit one sanitized line like:

```kotlin
logSink?.invoke("ai.provider.${providerId} model=$modelName POST $url headers=${headers.withRedactedValues()}")
```

This is the **only** logging done by provider models in PR1 — body content is NEVER logged. Wire `logSink` and `debug` from `AiClientFactory.createFromConfig` (which takes them from `AiConfig`).

- [ ] **Step 4: Update `AiComponent.init`** to set `config.logSink` from `LoggerFactory` if present:

```kotlin
// inside AiComponent.init, before AiClientFactory.createFromConfig(config):
if (config.logSink == null) {
    val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.ai")
    if (log != null) {
        config.logSink = { line -> log.info(line, emptyMap()) }
    }
}
```

- [ ] **Step 5: Write verification test**

```kotlin
// neton-ai/src/commonTest/kotlin/neton/ai/usage/RedactionVerificationTest.kt
package neton.ai.usage

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import neton.ai.AiClient
import neton.http.client.NetonHttpClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionVerificationTest {

    private fun factoryOf(engine: MockEngine) = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    @Test fun debugLogsDoNotContainApiKey() = runTest {
        val capturedLines = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers {
                openAiCompatible("openai") {
                    baseUrl = "https://api.example.com/v1"
                    apiKey = "sk-SECRET-DO-NOT-LEAK"
                }
            }
            routing { defaultModel = "openai:m" }
            debug = true
            logSink = { line -> capturedLines += line }
        }
        ai.generateText { user("hi") }
        ai.close()

        // The API key value MUST NOT appear anywhere in captured logs
        for (line in capturedLines) {
            assertFalse("sk-SECRET-DO-NOT-LEAK" in line,
                "API key leaked in log line: $line")
        }
        // At least one log line was emitted (verifies logSink is actually wired)
        assertTrue(capturedLines.isNotEmpty(), "expected at least one debug log line; logSink may not be wired")
        // The Authorization header should appear as <redacted>
        assertTrue(capturedLines.any { "Authorization" in it && "<redacted>" in it },
            "Expected Authorization=<redacted> in some log line; lines: $capturedLines")
    }

    @Test fun debugFalseEmitsNoLogs() = runTest {
        val capturedLines = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond("""{"choices":[{"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val ai = AiClient.create {
            httpClient = NetonHttpClient.createWithEngine(factoryOf(engine))
            providers { openAiCompatible("openai") { baseUrl = "https://x"; apiKey = "k" } }
            routing { defaultModel = "openai:m" }
            debug = false
            logSink = { line -> capturedLines += line }
        }
        ai.generateText { user("hi") }
        ai.close()
        assertTrue(capturedLines.isEmpty(), "no logs expected when debug=false; got: $capturedLines")
    }
}
```

- [ ] **Step 6: Run all tests** then **Step 7: Commit**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add neton-ai/src/commonMain/kotlin/neton/ai/AiClient.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiConfig.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/AiComponent.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/Redaction.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/internal/AiClientFactory.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleTextModel.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/openaicompatible/OpenAiCompatibleProvider.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicTextModel.kt \
    neton-ai/src/commonMain/kotlin/neton/ai/adapter/anthropic/AnthropicProvider.kt \
    neton-ai/src/commonTest/kotlin/neton/ai/usage/RedactionVerificationTest.kt
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "feat(ai): wire redaction into provider debug logs (closes PR0 Gate 13) + 2 verification tests"
```

---

## Task 20: Full module build + all tests + acceptance gate verification

**Files:** none modified (verification only). Also clean up any minor accumulated cruft.

- [ ] **Step 1: Full build**

```
./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:build 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL on all 5 native targets.

- [ ] **Step 2: All tests**

```
./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test 2>&1 | tail -15
```

Expected count (rough): 24 (AiError) + 9 (AiModelId) + 8 (Builder) + 7 (Router) + 7 (UsageAggregator) + 10 (ToolLoop) + 10 (OpenAi Req) + 17 (OpenAi Resp) + 3 (OpenAi Integ) + 12 (Anthropic Req) + ~12 (Anthropic Resp) + ~3 (Anthropic Integ) + 5 (AiConfigMerge) + 5 (StandaloneFactory) + 2 (ComponentBoot) + 2 (Redaction) = **~135 tests**, all pass.

- [ ] **Step 3: API hygiene grep — no `io.ktor.*` in public API**

```
grep -rn "io.ktor" /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonMain/kotlin/neton/ai/ --include="*.kt" 2>&1 | grep -v "/internal/" | grep -v "/adapter/"
```
Expected: **0 matches**. Adapter internal use of `kotlinx.serialization` for wire DTOs is OK (internal); but **`io.ktor.*` must not appear in adapter common files either** since adapters use `NetonHttpClient` API.

Wait — adapters use `NetonHttpClient` (PR0), not Ktor directly. So this grep should return 0 in adapter/ too.

Better grep:
```
grep -rEHn "^import io\.ktor\." /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonMain/kotlin/ 2>&1
```
Expected: **0 matches** (adapters use `neton.http.client.*`, never `io.ktor.*` directly).

- [ ] **Step 4: API hygiene grep — no `kotlinx.serialization.json.JsonElement` in public API**

```
grep -rEHn "kotlinx\.serialization\.json\.JsonElement|kotlinx\.serialization\.json\.JsonObject" /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonMain/kotlin/neton/ai/ --include="*.kt" 2>&1 | grep -v "/internal/" | grep -v "/adapter/.*/dto/" | grep -v "/adapter/.*Mapper\.kt"
```

Expected: **0 matches** outside of adapter internals and mappers.

- [ ] **Step 5: Dual-usage red lines**

```
grep -rEHn "^import neton\.(core|logging)" /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonMain/kotlin/ 2>&1
```

Expected: matches ONLY in `AiComponent.kt`. All standalone-path files MUST NOT import neton.core / neton.logging.

```
grep -rEHn "^import (neton\.core|neton\.logging|neton\.ai\.internal|neton\.http\.client\.internal)" /Users/zoujiaqing/projects/Neton/neton/neton-ai/src/commonTest/kotlin/neton/ai/AiClientFactoryStandaloneTest.kt 2>&1
```

Expected: **0 matches**.

- [ ] **Step 6: KtorHttpAdapter.kt unstaged change still untouched**

```
git -C /Users/zoujiaqing/projects/Neton/neton status --short 2>&1
```

Expected: ONLY ` M neton-http/src/commonMain/kotlin/neton/http/KtorHttpAdapter.kt` (the original unrelated change).

- [ ] **Step 7: Compile-time check of 6 closed-loop scenarios from PR1 requirements**

These are validated implicitly by the integration tests + standalone tests. Verify by re-running:

```
./gradlew -p /Users/zoujiaqing/projects/Neton/neton :neton-ai:macosArm64Test --tests "neton.ai.AiClientFactoryStandaloneTest" --tests "neton.ai.AiComponentBootTest" --tests "neton.ai.adapter.openaicompatible.OpenAiCompatibleIntegrationTest" --tests "neton.ai.adapter.anthropic.AnthropicIntegrationTest" --tests "neton.ai.ToolLoopTest" 2>&1 | tail -15
```

Map of 6 closed loops → covering tests:
1. Standalone usage (Mode 1) → `AiClientFactoryStandaloneTest.createWithMinimalConfigSucceeds` + `dualProviderSetupBuilds`
2. Neton Component (Mode 2) → `AiComponentBootTest.initBindsAiClientWhenConfigValid`
3. OpenAI non-stream mock → `OpenAiCompatibleIntegrationTest.nonStreamGenerateMapsRequestAndResponseEndToEnd`
4. Anthropic non-stream mock → `AnthropicIntegrationTest.<non-stream test>`
5. Tool loop multi-round + accumulation → `ToolLoopTest.toolLoopExecutesLocalToolAndContinues`
6. No Ktor public leakage → Step 3 grep (above)

- [ ] **Step 8: Sync PR1 plan with any adjustments made during execution**

If implementer made any adjustments (e.g., kotlinx-datetime added, or `JsonContentPolymorphicSerializer` fallback used), edit this plan to match — same pattern as PR0 commit `f2513b5`.

- [ ] **Step 9: Final commit (if any plan sync needed)**

```bash
git -C /Users/zoujiaqing/projects/Neton/neton add docs/superpowers/plans/2026-05-17-pr1-neton-ai-non-stream.md
git -C /Users/zoujiaqing/projects/Neton/neton commit -m "docs(ai): sync PR1 plan with execution adjustments"
```

---

## PR1 Self-Review

After completing all 20 tasks, verify:

### Spec coverage (§3 + §4.1 + §4.2)

- §3.2 Core types: all 10 created ✓
- §3.3 AiStreamEvent: NOT in PR1 (PR2 owns; SPI skeleton `AiStreamingTextModel` exists)
- §3.4 Public API generateText (non-streaming version): ✓
- §3.5 SPI: ProviderCallRequest/Response, AiProvider/AiTextModel/AiStreamingTextModel/AiEmbeddingModel/ProviderRegistry ✓
- §3.6 Routing: AiModelId, ModelRouter, RoutingConfig, ModelPolicy, DefaultModelRouter ✓
- §3.7 Tool loop non-streaming: ✓ (PR2 adds streaming version)
- §3.8 Cancellation: relies on Kotlin coroutines through `NetonHttpClient` (already proven in PR0)
- §3.9 AiComponent + DSL ✓
- §3.10 Config file format (TOML, camelCase): supported via `applyFileMap` ✓
- §3.11 Usage Recorder + AiUsageEvent + Noop + Logging ✓
- §3.12 Logging & redaction primitives wired (Task 19) ✓
- §3.13 Module structure: matches ✓
- §4.1 OpenAi-compat adapter: non-stream + error mapping ✓ (streaming PR2; embedding PR3)
- §4.2 Anthropic adapter: non-stream + error mapping ✓ (streaming PR2)

### PR1 hard constraints (carried from spec + PR0 review) - re-verification

1. ✅ No `io.ktor.*` in public API (Step 3 grep)
2. ✅ `AiClient.create` standalone works without Neton runtime (StandaloneTest)
3. ✅ `DefaultAiClient` uses `NetonHttpClient` exclusively
4. ✅ Provider adapters use `NetonHttpRequest` / `NetonHttpResponse`
5. ✅ API keys never in logs (RedactionVerificationTest)
6. ✅ `AiStreamingTextModel` / `AiEmbeddingModel` empty skeletons reserved for PR2/PR3
7. ✅ Fallback rules per spec §3.7 (ToolLoopTest covers all branches)

### Acceptance gates summary

- Build & test gates: ✅ (Steps 1-2)
- API hygiene gates: ✅ (Steps 3-5)
- Dual-usage gates: ✅ (StandaloneTest + ComponentBootTest)
- PR0 Gate 13 redaction: ✅ closed (Task 19)
- KtorHttpAdapter.kt untouched: ✅ (Step 6)

PR1 acceptance: **PASSED** — neton-ai v0.1 non-stream `generateText` is functionally complete; ready for PR2 (streamText + tool loop streaming + SSE mapping).

---

## PR1 done. Follow-ups for PR2/PR3

- **PR2** (streamText): add `stream` method to `AiStreamingTextModel`; add `AiStreamEvent` sealed hierarchy; add `OpenAiCompatibleStreamMapper` + `AnthropicStreamMapper` using `neton-http-client`'s SSE Flow ops (PR0 Task 9/10); add streaming tool loop to `DefaultAiClient`; add hard contract tests for spec §6.3 gates 14-21.
- **PR3** (router polish + embedding + examples): add `embed` method to `AiEmbeddingModel`; add `OpenAiCompatibleEmbeddingModel`; close any DRY risk noted in PR0 final review (HttpClientComponent field copy); create `examples/neton-ai-sample/`; write README; live smoke tasks.

The `HttpClientConfig` field-level merge DRY concern from PR0 final review (`HttpClientComponent` manually copies 4 fields to `NetonHttpClient.create { ... }`) was NOT addressed in PR1. PR3 should add an `HttpClientConfig.copyOf(other)` helper or a `NetonHttpClient.create(config: HttpClientConfig)` overload, then update `HttpClientComponent` to use it.

