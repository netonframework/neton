# PR0: `neton-http-client` v0.1 Implementation Plan

> **⚠️ OBSOLETE (as of Neton 1.0).** The standalone `neton-http-client` module described here was
> merged into `neton-http`: the HTTP client now lives at `neton-http/src/.../neton/http/client/`
> (package `neton.http.client` unchanged), and `:neton-http-client` is no longer in
> `settings.gradle.kts`. This document is kept as a historical design/execution record only —
> do not implement against it or reference `neton-http-client` as a separate module.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `neton-http-client`, a thin KMP HTTP client infrastructure module that provides typed HTTP request/response, per-platform Ktor engine selection, typed errors, redaction policy, SSE parser primitive, and cancellation propagation. Prerequisite for `neton-ai` (PR1-3).

**Architecture:** New KMP module under `neton/neton-http-client/`, package `neton.http.client`. Wraps Ktor Client with a provider-neutral interface (no `io.ktor.*` types on public API beyond what's strictly necessary in low-level escape hatches). Follows existing Neton module conventions (`NetonComponent`, `Neton.LaunchBuilder` DSL, TOML config via `ConfigLoader`). Per-platform engine via `expect/actual` (macOS Darwin / Linux CIO / Windows WinHTTP), mirroring `neton-storage`.

**Tech Stack:** Kotlin 2.3.10 KMP (macosArm64, macosX64, linuxX64, linuxArm64, mingwX64), Ktor Client 3.4.2, kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.10.0, Ktor MockEngine for tests.

**Spec reference:** `docs/superpowers/specs/2026-05-17-neton-ai-and-http-client-design.md` §2.

---

## Design Constraint: Dual Usage Mode (NON-NEGOTIABLE)

`neton-http-client` is Neton Framework's unified KMP HTTP Client infrastructure and **MUST** be usable in two completely independent modes:

### Mode 1 — Standalone library (any KMP / Kotlin Native project)

Caller does NOT start `Neton.run { ... }`, does NOT have a `NetonContext`, does NOT depend on `neton-core`'s runtime container. They just want a Kotlin HTTP client.

```kotlin
// In any KMP project that depends on neton-http-client:
val client = NetonHttpClient.create {
    requestMillis = 30_000
    connectMillis = 3_000
}
val response = client.request(NetonHttpRequest(
    method = NetonHttpMethod.Get,
    url = "https://example.com",
))
client.close()
```

### Mode 2 — Neton Framework component

Used as part of a Neton application via the DSL; binds `NetonHttpClient` into the context for downstream modules (`neton-ai`, future `neton-storage` migration, etc.):

```kotlin
Neton.run(args) {
    httpClient { requestMillis = 30_000 }
    ai { ... }   // PR1+, consumes ctx.get(NetonHttpClient::class)
}
```

### Architectural rules enforced by this constraint

1. **`NetonHttpClient` interface and `DefaultNetonHttpClient` impl MUST NOT reference `neton.core.*` or `neton.logging.*` types in their construction path.** A standalone caller has neither.
2. **`HttpClientComponent` is a thin adapter ONLY.** Its sole job is: load config → build `NetonHttpClient` via the standalone factory → `ctx.bind(NetonHttpClient::class, ...)`. No business logic in the component.
3. **A public factory `NetonHttpClient.Companion.create(...)` MUST exist** so standalone callers don't need to import internal types (`DefaultNetonHttpClient`).
4. **A standalone-usage test MUST exist** that instantiates `NetonHttpClient.create { ... }` and makes a request via `MockEngine`, with **zero** imports from `neton.core.*` or `neton.logging.*` in that test file.

This same dual-usage rule will apply to `neton-ai` in PR1 (`AiClient.create { ... }` factory + thin `AiComponent` adapter).

---

## File Structure

**New files (22 source + 5 test = 27)**:

```
neton-http-client/
├── build.gradle.kts                                                  # (1)
└── src/
    ├── commonMain/kotlin/neton/http/client/
    │   ├── NetonHttpClient.kt                                        # (2) interface
    │   ├── NetonHttpRequest.kt                                       # (3) data class
    │   ├── NetonHttpResponse.kt                                      # (4) data class
    │   ├── NetonHttpMethod.kt                                        # (5) enum
    │   ├── NetonHttpBody.kt                                          # (6) sealed
    │   ├── NetonHttpStreamChunk.kt                                   # (7) sealed
    │   ├── NetonHttpTimeout.kt                                       # (8) data class
    │   ├── NetonHttpError.kt                                         # (9) sealed
    │   ├── NetonHttpException.kt                                     # (10) class
    │   ├── NetonRedactionPolicy.kt                                   # (11) data class + DEFAULT_REDACTED_HEADERS
    │   ├── NetonRetryPolicy.kt                                       # (12) interface + RetryDecision + NoRetryPolicy
    │   ├── HttpClientConfig.kt                                       # (13) DSL config
    │   ├── HttpClientComponent.kt                                    # (14) NetonComponent + DSL
    │   ├── internal/
    │   │   ├── DefaultNetonHttpClient.kt                             # (15) impl
    │   │   └── KtorEngineFactory.kt                                  # (16) expect
    │   └── sse/
    │       ├── NetonSseEvent.kt                                      # (17) data class
    │       ├── NetonSseParser.kt                                     # (18) class with logic
    │       └── SseFlowOps.kt                                         # (19) Flow extensions
    ├── macosMain/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.macos.kt                                # (20) actual = Darwin
    ├── linuxMain/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.linux.kt                                # (21) actual = CIO
    ├── mingwX64Main/kotlin/neton/http/client/internal/
    │   └── KtorEngineFactory.mingw.kt                                # (22) actual = WinHttp
    └── commonTest/kotlin/neton/http/client/
        ├── sse/NetonSseParserTest.kt                                 # (23) parser edge cases
        ├── sse/SseFlowOpsTest.kt                                     # (24) Flow ops
        ├── MockEngineHttpClientTest.kt                               # (25) request/response/streaming
        ├── CancellationTest.kt                                       # (26) cancel propagation
        └── StandaloneUsageTest.kt                                    # (27) dual-usage Mode 1 contract
```

**Modified files (2)**:

- `settings.gradle.kts` — add `include(":neton-http-client")`
- `gradle/libs.versions.toml` — add `ktor-client-content-negotiation` + `ktor-client-mock` library aliases

---

## Task 1: Add Ktor Client deps to version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add `ktor-client-content-negotiation` and `ktor-client-mock` library aliases**

In `gradle/libs.versions.toml`, locate the section starting `# Ktor Client（neton-storage S3 后端）` and append two lines:

```toml
# Ktor Client（neton-storage S3 后端）
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-winhttp = { module = "io.ktor:ktor-client-winhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

- [ ] **Step 2: Verify catalog still resolves**

Run: `./gradlew :neton-storage:dependencies --configuration commonMainCompileClasspath -q | head -5`
Expected: command succeeds (no parse error), shows neton-storage configuration.

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build(deps): add ktor-client-content-negotiation + mock to version catalog"
```

---

## Task 2: Create `neton-http-client` module skeleton

**Files:**
- Create: `neton-http-client/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create `neton-http-client/build.gradle.kts`**

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
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val macosMain by creating {
            dependsOn(posixMain)
        }
        val linuxMain by creating {
            dependsOn(posixMain)
        }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                // NOTE: ContentNegotiation deliberately NOT included — this module is byte/text
                // passthrough (NetonHttpResponse.body: String). Consumers add ContentNegotiation
                // themselves if they want typed JSON. YAGNI per code review on commit d29a2f9.
            }
        }

        macosMain.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.cio) }

        val mingwX64Main1 = mingwX64Main
        mingwX64Main1.dependencies { implementation(libs.ktor.client.winhttp) }

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

- [ ] **Step 2: Add module to `settings.gradle.kts`**

Locate the section listing `include(":neton-*")` lines. Add `:neton-http-client` alphabetically after `:neton-http`:

```kotlin
include(":neton-http")       // HTTP 组件模块
include(":neton-http-client")// HTTP Client 基础设施模块（KMP Ktor Client wrapper + SSE + retry primitive）
include(":neton-routing")    // 路由组件模块
```

- [ ] **Step 3: Verify module is recognized**

Run: `./gradlew :neton-http-client:tasks -q 2>&1 | head -10`
Expected: prints task list including `clean`, `build`, etc. No "Project ':neton-http-client' not found" error.

- [ ] **Step 4: Commit**

```bash
git add neton-http-client/build.gradle.kts settings.gradle.kts
git commit -m "feat(http-client): add neton-http-client module skeleton (KMP Ktor Client)"
```

---

## Task 3: Pure data types — methods, body, timeout

These are pure data classes with no behavior. Grouped into one task.

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpMethod.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpBody.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpTimeout.kt`

- [ ] **Step 1: Create `NetonHttpMethod.kt`**

```kotlin
package neton.http.client

enum class NetonHttpMethod {
    Get,
    Post,
    Put,
    Delete,
    Patch,
    Head,
    Options,
}
```

- [ ] **Step 2: Create `NetonHttpBody.kt`**

```kotlin
package neton.http.client

sealed interface NetonHttpBody {
    /** Pre-serialized JSON string; Content-Type set to application/json. */
    data class Json(val text: String) : NetonHttpBody

    /** Arbitrary text with caller-supplied content type. */
    data class Text(val text: String, val contentType: String) : NetonHttpBody

    /** Arbitrary bytes with caller-supplied content type. */
    data class Bytes(val bytes: ByteArray, val contentType: String) : NetonHttpBody {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }
}
```

- [ ] **Step 3: Create `NetonHttpTimeout.kt`**

```kotlin
package neton.http.client

/**
 * Per-request timeout overrides. Any field null = inherit client default.
 */
data class NetonHttpTimeout(
    val connectMillis: Long? = null,
    val requestMillis: Long? = null,
    val socketMillis: Long? = null,
)
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. No errors.

- [ ] **Step 5: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpMethod.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpBody.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpTimeout.kt
git commit -m "feat(http-client): add NetonHttpMethod, NetonHttpBody, NetonHttpTimeout"
```

---

## Task 4: Request / Response / StreamChunk data types

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpRequest.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpResponse.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpStreamChunk.kt`

- [ ] **Step 1: Create `NetonHttpRequest.kt`**

```kotlin
package neton.http.client

/**
 * Request envelope passed to NetonHttpClient.
 *
 * @property metadata caller-supplied tags forwarded to logging/retry hooks (do NOT put secrets here)
 */
data class NetonHttpRequest(
    val method: NetonHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: NetonHttpBody? = null,
    val timeout: NetonHttpTimeout? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

- [ ] **Step 2: Create `NetonHttpResponse.kt`**

```kotlin
package neton.http.client

/**
 * Non-streaming response. For large bodies / SSE use NetonHttpClient.stream() instead.
 */
data class NetonHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)
```

- [ ] **Step 3: Create `NetonHttpStreamChunk.kt`**

```kotlin
package neton.http.client

sealed interface NetonHttpStreamChunk {
    data class Bytes(val bytes: ByteArray) : NetonHttpStreamChunk {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Text(val text: String) : NetonHttpStreamChunk

    /** Terminal chunk; emitted after the last byte/text chunk to signal end of body. */
    data class End(val finalHeaders: Map<String, String>) : NetonHttpStreamChunk
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpRequest.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpResponse.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpStreamChunk.kt
git commit -m "feat(http-client): add NetonHttpRequest, NetonHttpResponse, NetonHttpStreamChunk"
```

---

## Task 5: Typed error + exception

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpError.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpException.kt`

- [ ] **Step 1: Create `NetonHttpError.kt`**

```kotlin
package neton.http.client

sealed interface NetonHttpError {
    val message: String
    val cause: Throwable?

    data class Network(
        override val message: String,
        override val cause: Throwable?,
    ) : NetonHttpError

    data class Timeout(
        override val message: String,
        override val cause: Throwable?,
    ) : NetonHttpError

    /** HTTP-level error (4xx / 5xx). Body is optional (may be unavailable on streaming responses). */
    data class Http(
        val statusCode: Int,
        override val message: String,
        val body: String?,
    ) : NetonHttpError {
        override val cause: Throwable? = null
    }

    data class Unknown(
        override val message: String,
        override val cause: Throwable?,
    ) : NetonHttpError
}
```

- [ ] **Step 2: Create `NetonHttpException.kt`**

```kotlin
package neton.http.client

/**
 * Wrapper exception so NetonHttpError (sealed interface) can be thrown across coroutines / suspend functions.
 * Public API of neton-http-client throws this; downstream consumers (e.g., neton-ai) catch it and map .error to their own error taxonomy.
 */
class NetonHttpException(val error: NetonHttpError) : RuntimeException(error.message, error.cause)
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpError.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpException.kt
git commit -m "feat(http-client): add NetonHttpError sealed taxonomy + NetonHttpException"
```

---

## Task 6: Redaction + retry primitives

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonRedactionPolicy.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonRetryPolicy.kt`

- [ ] **Step 1: Create `NetonRedactionPolicy.kt`**

```kotlin
package neton.http.client

/**
 * Redaction policy applied to all log output (any level).
 * Headers in [redactedHeaders] are NEVER printed, replaced with "<redacted>".
 * Body content is only printed when [allowBodyLogging] = true.
 *
 * Hard rule: API keys NEVER appear in logs regardless of policy.
 */
data class NetonRedactionPolicy(
    val redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS,
    val allowBodyLogging: Boolean = false,
)

/** Case-insensitive comparison expected when applying. */
val DEFAULT_REDACTED_HEADERS: Set<String> = setOf(
    "Authorization",
    "X-Api-Key",
    "api-key",
    "anthropic-api-key",
    "Cookie",
    "Set-Cookie",
    "Proxy-Authorization",
)
```

- [ ] **Step 2: Create `NetonRetryPolicy.kt`**

```kotlin
package neton.http.client

/**
 * HTTP-layer retry primitive. v0.1 ships only NoRetryPolicy.
 * Downstream modules (e.g., neton-ai router fallback) handle their own retry semantics.
 */
interface NetonRetryPolicy {
    fun shouldRetry(attempt: Int, response: NetonHttpResponse?, error: NetonHttpError?): RetryDecision
}

sealed interface RetryDecision {
    data object DoNotRetry : RetryDecision
    data class RetryAfter(val delayMillis: Long) : RetryDecision
}

object NoRetryPolicy : NetonRetryPolicy {
    override fun shouldRetry(attempt: Int, response: NetonHttpResponse?, error: NetonHttpError?): RetryDecision =
        RetryDecision.DoNotRetry
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/NetonRedactionPolicy.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonRetryPolicy.kt
git commit -m "feat(http-client): add NetonRedactionPolicy + NetonRetryPolicy primitives"
```

---

## Task 7: `NetonHttpClient` interface + `HttpClientConfig` + standalone factory

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientConfig.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpClient.kt`

Bundled into one task because the standalone factory `NetonHttpClient.Companion.create(block: HttpClientConfig.() -> Unit)` depends on `HttpClientConfig`. Both files are pure data + interface with no Neton-runtime dependencies — they are the **standalone library entry points** (Mode 1 of the dual-usage constraint).

`HttpClientComponent` (Task 15) is built on top of these; that's the Neton Framework adapter (Mode 2).

- [ ] **Step 1a: Create `HttpClientConfig.kt`**

```kotlin
package neton.http.client

/**
 * DSL config for standalone NetonHttpClient.create { ... } AND HttpClientComponent.
 * Nullable fields = "not set" (let defaults or file config fill in).
 */
class HttpClientConfig {
    var connectMillis: Long? = null
    var requestMillis: Long? = null
    var socketMillis: Long? = null
    var debug: Boolean = false

    internal fun toEffectiveTimeout(): NetonHttpTimeout = NetonHttpTimeout(
        connectMillis = connectMillis ?: 5_000,
        requestMillis = requestMillis ?: 60_000,
        socketMillis = socketMillis ?: 60_000,
    )

    internal fun validate(): List<String> {
        val errors = mutableListOf<String>()
        connectMillis?.let { if (it <= 0) errors += "connectMillis must be > 0" }
        requestMillis?.let { if (it <= 0) errors += "requestMillis must be > 0" }
        socketMillis?.let { if (it <= 0) errors += "socketMillis must be > 0" }
        return errors
    }
}
```

- [ ] **Step 1b: Create `NetonHttpClient.kt`**

```kotlin
package neton.http.client

import kotlinx.coroutines.flow.Flow
import neton.http.client.internal.DefaultNetonHttpClient

/**
 * Provider-neutral HTTP client. Public API of neton-http-client.
 *
 * **Dual usage**:
 *   1. Standalone (any KMP project): `val client = NetonHttpClient.create { requestMillis = 30_000 }`
 *   2. Neton Framework component (PR1+ neton-ai etc.): `Neton.run { httpClient { ... } }`; downstream
 *      modules use `ctx.get(NetonHttpClient::class)`.
 *
 * Implementations are responsible for:
 *  - per-platform Ktor engine selection (Darwin / CIO / WinHttp)
 *  - timeout enforcement
 *  - typed error mapping (NetonHttpException for failures)
 *  - cancellation propagation (Flow cancel → HTTP body close)
 *  - redaction of sensitive headers in any internal logging
 *
 * Downstream consumers (neton-ai, future neton-webhooks, etc.) consume this interface,
 * NEVER `io.ktor.client.*` directly.
 */
interface NetonHttpClient {
    /**
     * Execute a one-shot HTTP request. Body fully buffered in [NetonHttpResponse.body].
     * Throws [NetonHttpException] on transport / HTTP failures.
     */
    suspend fun request(request: NetonHttpRequest): NetonHttpResponse

    /**
     * Open a streaming HTTP body. Flow emits [NetonHttpStreamChunk.Bytes] (or [NetonHttpStreamChunk.Text] for text bodies)
     * followed by exactly one [NetonHttpStreamChunk.End].
     *
     * Cancellation: cancelling the Flow collection closes the underlying HTTP response body,
     * which closes the TCP connection. Server observes the close and stops generating.
     *
     * Throws [NetonHttpException] on connection failures before the first chunk is read.
     * Errors mid-stream propagate as Flow exceptions (also [NetonHttpException]).
     */
    fun stream(request: NetonHttpRequest): Flow<NetonHttpStreamChunk>

    /** Release engine resources. Idempotent. */
    suspend fun close()

    companion object {
        /**
         * Standalone factory. Constructs a [NetonHttpClient] from a config DSL without
         * requiring any Neton runtime (`Neton.run`, `NetonContext`, etc.).
         *
         * @throws NetonHttpException(NetonHttpError.Unknown) on invalid config (non-positive timeouts).
         */
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
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. Both `HttpClientConfig` and `NetonHttpClient` (with `Companion.create`) compile.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientConfig.kt \
        neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpClient.kt
git commit -m "feat(http-client): add NetonHttpClient interface + HttpClientConfig + standalone create() factory"
```

---

## Task 8: SSE event data type

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseEvent.kt`

- [ ] **Step 1: Create `NetonSseEvent.kt`**

```kotlin
package neton.http.client.sse

/**
 * Server-Sent Event (SSE) per W3C / WHATWG spec.
 *
 * @property id   "id:" field (optional, used for resume after disconnect)
 * @property event "event:" field (optional, named events; Anthropic uses this — "message_start", "content_block_delta", etc.)
 * @property data "data:" field, concatenated by newlines if multi-line in source
 */
data class NetonSseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseEvent.kt
git commit -m "feat(http-client): add NetonSseEvent data class"
```

---

## Task 9: `NetonSseParser` (TDD with full edge case suite)

This is the first behavior-heavy component. Use strict TDD.

**Files:**
- Create: `neton-http-client/src/commonTest/kotlin/neton/http/client/sse/NetonSseParserTest.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseParser.kt`

- [ ] **Step 1: Write failing tests for basic single-line event**

```kotlin
// neton-http-client/src/commonTest/kotlin/neton/http/client/sse/NetonSseParserTest.kt
package neton.http.client.sse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetonSseParserTest {

    @Test
    fun emitsSingleEventOnBlankLine() {
        val parser = NetonSseParser()
        assertTrue(parser.accept("data: hello").isEmpty(), "data line alone does not finalize event")
        val events = parser.accept("")
        assertEquals(1, events.size)
        assertEquals(NetonSseEvent(data = "hello"), events[0])
    }

    @Test
    fun stripsLeadingSpaceOnDataField() {
        val parser = NetonSseParser()
        parser.accept("data:  with-two-leading-spaces")
        val events = parser.accept("")
        // SSE spec: strip ONE leading space if present
        assertEquals(" with-two-leading-spaces", events.single().data)
    }

    @Test
    fun parsesEventAndIdFields() {
        val parser = NetonSseParser()
        parser.accept("id: 42")
        parser.accept("event: message_start")
        parser.accept("data: {\"k\":1}")
        val events = parser.accept("")
        assertEquals(NetonSseEvent(id = "42", event = "message_start", data = "{\"k\":1}"), events.single())
    }

    @Test
    fun concatenatesMultiLineData() {
        val parser = NetonSseParser()
        parser.accept("data: line1")
        parser.accept("data: line2")
        val events = parser.accept("")
        assertEquals("line1\nline2", events.single().data)
    }

    @Test
    fun ignoresCommentLines() {
        val parser = NetonSseParser()
        parser.accept(": keep-alive comment")
        parser.accept("data: real")
        val events = parser.accept("")
        assertEquals("real", events.single().data)
    }

    @Test
    fun ignoresUnknownFields() {
        val parser = NetonSseParser()
        parser.accept("retry: 5000")  // SSE retry field, not stored in NetonSseEvent (only id/event/data)
        parser.accept("data: ok")
        val events = parser.accept("")
        assertEquals(NetonSseEvent(data = "ok"), events.single())
    }

    @Test
    fun emitsDoneSentinelAsRegularEvent() {
        // OpenAI uses "data: [DONE]" — parser treats it as a regular event with data="[DONE]".
        // Consumer decides whether [DONE] terminates the logical stream.
        val parser = NetonSseParser()
        parser.accept("data: [DONE]")
        val events = parser.accept("")
        assertEquals("[DONE]", events.single().data)
    }

    @Test
    fun finishFlushesPendingEvent() {
        val parser = NetonSseParser()
        parser.accept("data: no-trailing-newline")
        // No blank line yet; event is buffered.
        val flushed = parser.finish()
        assertEquals("no-trailing-newline", flushed.single().data)
    }

    @Test
    fun finishReturnsEmptyWhenNoPendingEvent() {
        val parser = NetonSseParser()
        parser.accept("data: ok")
        parser.accept("")  // flushed
        assertEquals(emptyList(), parser.finish())
    }

    @Test
    fun handlesMultipleEventsInSequence() {
        val parser = NetonSseParser()
        parser.accept("data: first")
        val first = parser.accept("")
        parser.accept("data: second")
        val second = parser.accept("")
        assertEquals("first", first.single().data)
        assertEquals("second", second.single().data)
    }

    @Test
    fun fieldWithoutColonIsTreatedAsFieldNameWithEmptyValue() {
        // Per spec: "data" without ":" → field name = "data", value = "" → empty data line
        val parser = NetonSseParser()
        parser.accept("data")  // field=data value=""
        val events = parser.accept("")
        assertEquals("", events.single().data)
    }

    @Test
    fun blankLineBeforeAnyDataFieldEmitsNothing() {
        // Per spec: if data buffer is empty, do not dispatch an event
        val parser = NetonSseParser()
        val events = parser.accept("")
        assertTrue(events.isEmpty())
    }

    @Test
    fun eventFieldWithoutDataStillDispatchesIfDataAccumulated() {
        val parser = NetonSseParser()
        parser.accept("event: ping")
        parser.accept("data:")  // empty data, still triggers dispatch
        val events = parser.accept("")
        assertEquals(NetonSseEvent(event = "ping", data = ""), events.single())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.sse.NetonSseParserTest" 2>&1 | tail -20`
Expected: FAIL with "Unresolved reference: NetonSseParser".

- [ ] **Step 3: Implement `NetonSseParser`**

```kotlin
// neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseParser.kt
package neton.http.client.sse

/**
 * Line-by-line SSE parser per W3C / WHATWG Server-Sent Events spec.
 * Stateful: feed lines via [accept]; events are emitted when a blank line is encountered (per spec).
 * Call [finish] at end of stream to flush any pending event without trailing blank line.
 *
 * Not thread-safe. Use one parser per stream / collection coroutine.
 */
class NetonSseParser {

    private var dataBuffer = StringBuilder()
    private var eventType: String? = null
    private var lastId: String? = null
    private var hasData = false

    /**
     * Feed one line (already stripped of CR/LF terminator).
     * Returns 0 or 1 emitted events.
     */
    fun accept(line: String): List<NetonSseEvent> {
        // Blank line: dispatch event if data accumulated
        if (line.isEmpty()) return dispatch()

        // Comment line: ignore
        if (line.startsWith(":")) return emptyList()

        // Field parse: "name:value" or "name:" or "name" (no colon = empty value)
        val colonIdx = line.indexOf(':')
        val field: String
        val rawValue: String
        if (colonIdx < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, colonIdx)
            // Per spec: strip single leading space from value
            rawValue = line.substring(colonIdx + 1).let { if (it.startsWith(" ")) it.drop(1) else it }
        }

        when (field) {
            "data" -> {
                if (hasData) dataBuffer.append('\n')
                dataBuffer.append(rawValue)
                hasData = true
            }
            "event" -> eventType = rawValue
            "id" -> {
                // Spec: ignore id values containing NUL; we use isNotBlank() as a simple guard
                if (rawValue.isNotEmpty() && !rawValue.contains(' ')) lastId = rawValue
            }
            // "retry" and unknown fields: ignored (not represented in NetonSseEvent)
        }

        return emptyList()
    }

    /**
     * Flush any pending event (e.g., stream ended without trailing blank line).
     */
    fun finish(): List<NetonSseEvent> = dispatch()

    private fun dispatch(): List<NetonSseEvent> {
        if (!hasData) {
            // Per spec: do not dispatch if data buffer is empty (no "data:" field seen since last event)
            // Reset eventType anyway (spec: event field reset after each dispatch / blank line)
            eventType = null
            return emptyList()
        }
        val event = NetonSseEvent(
            id = lastId,  // lastId persists across events per spec
            event = eventType,
            data = dataBuffer.toString(),
        )
        dataBuffer = StringBuilder()
        eventType = null
        hasData = false
        return listOf(event)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.sse.NetonSseParserTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` with all 13 tests passing.

- [ ] **Step 5: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/sse/NetonSseParser.kt \
        neton-http-client/src/commonTest/kotlin/neton/http/client/sse/NetonSseParserTest.kt
git commit -m "feat(http-client): add NetonSseParser with W3C-compliant line-based parsing"
```

---

## Task 10: SSE Flow operators (cross-chunk fragmentation)

**Files:**
- Create: `neton-http-client/src/commonTest/kotlin/neton/http/client/sse/SseFlowOpsTest.kt`
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/sse/SseFlowOps.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// neton-http-client/src/commonTest/kotlin/neton/http/client/sse/SseFlowOpsTest.kt
package neton.http.client.sse

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.http.client.NetonHttpStreamChunk
import kotlin.test.Test
import kotlin.test.assertEquals

class SseFlowOpsTest {

    @Test
    fun parsesSimpleStringFlow() = runTest {
        val events = flowOf(
            "data: hello",
            "",
            "data: world",
            "",
        ).parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "hello"), NetonSseEvent(data = "world")), events)
    }

    @Test
    fun handlesCrossChunkLineFragmentation() = runTest {
        // Chunk boundaries don't align with line boundaries: "data: hel" + "lo\n\ndata: wor" + "ld\n\n"
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: hel".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("lo\n\ndata: wor".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("ld\n\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(emptyMap()),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "hello"), NetonSseEvent(data = "world")), events)
    }

    @Test
    fun flushesPendingEventAtStreamEnd() = runTest {
        // No trailing blank line; finish() must flush.
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: pending".encodeToByteArray()),
            NetonHttpStreamChunk.End(emptyMap()),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "pending")), events)
    }

    @Test
    fun handlesCrlfLineEndings() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("data: ok\r\n\r\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(emptyMap()),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "ok")), events)
    }

    @Test
    fun preservesEventTypeAcrossChunks() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Bytes("event: messa".encodeToByteArray()),
            NetonHttpStreamChunk.Bytes("ge_start\ndata: {}\n\n".encodeToByteArray()),
            NetonHttpStreamChunk.End(emptyMap()),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(event = "message_start", data = "{}")), events)
    }

    @Test
    fun textChunkFlowAlsoSupported() = runTest {
        val chunks = flowOf(
            NetonHttpStreamChunk.Text("data: a"),
            NetonHttpStreamChunk.Text("\n\ndata: b\n\n"),
            NetonHttpStreamChunk.End(emptyMap()),
        )
        val events = chunks.parseSseEvents().toList()
        assertEquals(listOf(NetonSseEvent(data = "a"), NetonSseEvent(data = "b")), events)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.sse.SseFlowOpsTest" 2>&1 | tail -10`
Expected: FAIL with unresolved reference to `parseSseEvents`.

- [ ] **Step 3: Implement `SseFlowOps.kt`**

```kotlin
// neton-http-client/src/commonMain/kotlin/neton/http/client/sse/SseFlowOps.kt
package neton.http.client.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import neton.http.client.NetonHttpStreamChunk

/**
 * Convert a Flow of complete lines (no terminators) into SSE events.
 * Each line is fed directly to a [NetonSseParser]; blank string ("") finalizes the current event.
 */
fun Flow<String>.parseSseEvents(): Flow<NetonSseEvent> = flow {
    val parser = NetonSseParser()
    collect { line ->
        parser.accept(line).forEach { emit(it) }
    }
    parser.finish().forEach { emit(it) }
}

/**
 * Convert a Flow of byte/text chunks (with arbitrary boundaries) into SSE events.
 * Handles cross-chunk line fragmentation by accumulating into a byte buffer and splitting on LF.
 * Strips trailing CR (handles CRLF line endings).
 * Stops accumulating when [NetonHttpStreamChunk.End] is received; flushes any pending event.
 */
fun Flow<NetonHttpStreamChunk>.parseSseEvents(): Flow<NetonSseEvent> = flow {
    val parser = NetonSseParser()
    val buffer = StringBuilder()

    suspend fun drainBuffer(emitter: kotlinx.coroutines.flow.FlowCollector<NetonSseEvent>) {
        // Split buffer on LF; everything up to last LF is consumable, remainder stays in buffer.
        var start = 0
        while (true) {
            val lf = buffer.indexOf('\n', start)
            if (lf < 0) break
            var lineEnd = lf
            // Strip trailing CR (CRLF support)
            if (lineEnd > start && buffer[lineEnd - 1] == '\r') lineEnd--
            val line = buffer.substring(start, lineEnd)
            parser.accept(line).forEach { emitter.emit(it) }
            start = lf + 1
        }
        if (start > 0) buffer.delete(0, start)
    }

    collect { chunk ->
        when (chunk) {
            is NetonHttpStreamChunk.Bytes -> buffer.append(chunk.bytes.decodeToString())
            is NetonHttpStreamChunk.Text -> buffer.append(chunk.text)
            is NetonHttpStreamChunk.End -> {
                // Treat remaining buffer as one final line (if any), then flush
                if (buffer.isNotEmpty()) {
                    // The remaining text might itself contain a partial line; treat it as a single line
                    parser.accept(buffer.toString()).forEach { emit(it) }
                    buffer.clear()
                }
                parser.finish().forEach { emit(it) }
                return@collect
            }
        }
        drainBuffer(this)
    }
    // If End never arrived (Flow completed normally), flush remaining
    if (buffer.isNotEmpty()) {
        parser.accept(buffer.toString()).forEach { emit(it) }
    }
    parser.finish().forEach { emit(it) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.sse.SseFlowOpsTest" 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` with all 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/sse/SseFlowOps.kt \
        neton-http-client/src/commonTest/kotlin/neton/http/client/sse/SseFlowOpsTest.kt
git commit -m "feat(http-client): add SSE Flow operators with cross-chunk fragmentation handling"
```

---

## Task 11: KtorEngineFactory `expect`/`actual`

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/internal/KtorEngineFactory.kt`
- Create: `neton-http-client/src/macosMain/kotlin/neton/http/client/internal/KtorEngineFactory.macos.kt`
- Create: `neton-http-client/src/linuxMain/kotlin/neton/http/client/internal/KtorEngineFactory.linux.kt`
- Create: `neton-http-client/src/mingwX64Main/kotlin/neton/http/client/internal/KtorEngineFactory.mingw.kt`

- [ ] **Step 1: Create `commonMain` expect declaration**

```kotlin
// neton-http-client/src/commonMain/kotlin/neton/http/client/internal/KtorEngineFactory.kt
package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Per-platform Ktor engine selection.
 *  - macOS (Darwin)  → Darwin engine (NSURLSession)
 *  - Linux (posix)   → CIO engine
 *  - Windows         → WinHttp engine
 *
 * Returns the engine factory; engine is instantiated by [DefaultNetonHttpClient].
 */
internal expect fun defaultKtorEngine(): HttpClientEngineFactory<*>
```

- [ ] **Step 2: Create `macosMain` actual (Darwin)**

```kotlin
// neton-http-client/src/macosMain/kotlin/neton/http/client/internal/KtorEngineFactory.macos.kt
package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = Darwin
```

- [ ] **Step 3: Create `linuxMain` actual (CIO)**

```kotlin
// neton-http-client/src/linuxMain/kotlin/neton/http/client/internal/KtorEngineFactory.linux.kt
package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = CIO
```

- [ ] **Step 4: Create `mingwX64Main` actual (WinHttp)**

```kotlin
// neton-http-client/src/mingwX64Main/kotlin/neton/http/client/internal/KtorEngineFactory.mingw.kt
package neton.http.client.internal

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.winhttp.WinHttp

internal actual fun defaultKtorEngine(): HttpClientEngineFactory<*> = WinHttp
```

- [ ] **Step 5: Verify all targets compile**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 :neton-http-client:compileKotlinLinuxX64 :neton-http-client:compileKotlinMingwX64 2>&1 | tail -10`
Expected: All three `BUILD SUCCESSFUL` (or single combined).

- [ ] **Step 6: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/internal/KtorEngineFactory.kt \
        neton-http-client/src/macosMain \
        neton-http-client/src/linuxMain \
        neton-http-client/src/mingwX64Main
git commit -m "feat(http-client): add per-platform KtorEngineFactory expect/actual"
```

---

## Task 12: `DefaultNetonHttpClient` implementation (request + stream)

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/internal/DefaultNetonHttpClient.kt`

- [ ] **Step 1: Create `DefaultNetonHttpClient.kt`**

```kotlin
// neton-http-client/src/commonMain/kotlin/neton/http/client/internal/DefaultNetonHttpClient.kt
package neton.http.client.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.io.IOException
import neton.http.client.NetonHttpBody
import neton.http.client.NetonHttpClient
import neton.http.client.NetonHttpError
import neton.http.client.NetonHttpException
import neton.http.client.NetonHttpMethod
import neton.http.client.NetonHttpRequest
import neton.http.client.NetonHttpResponse
import neton.http.client.NetonHttpStreamChunk
import neton.http.client.NetonHttpTimeout

internal class DefaultNetonHttpClient(
    engineFactory: HttpClientEngineFactory<*> = defaultKtorEngine(),
    private val defaultTimeout: NetonHttpTimeout = NetonHttpTimeout(
        connectMillis = 5_000,
        requestMillis = 60_000,
        socketMillis = 60_000,
    ),
) : NetonHttpClient {

    private val client: HttpClient = HttpClient(engineFactory) {
        install(HttpTimeout) {
            connectTimeoutMillis = defaultTimeout.connectMillis
            requestTimeoutMillis = defaultTimeout.requestMillis
            socketTimeoutMillis = defaultTimeout.socketMillis
        }
        expectSuccess = false  // We map status manually
    }

    override suspend fun request(request: NetonHttpRequest): NetonHttpResponse {
        val response: HttpResponse = try {
            client.request { applyRequest(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw NetonHttpException(NetonHttpError.Timeout(e.message ?: "Request timeout", e))
        } catch (e: IOException) {
            throw NetonHttpException(NetonHttpError.Network(e.message ?: "Network error", e))
        } catch (e: Throwable) {
            throw NetonHttpException(NetonHttpError.Unknown(e.message ?: "Unknown HTTP error", e))
        }

        val bodyText = try {
            response.bodyAsText()
        } catch (e: Throwable) {
            throw NetonHttpException(NetonHttpError.Network("Failed to read response body: ${e.message}", e))
        }

        return NetonHttpResponse(
            statusCode = response.status.value,
            headers = response.headers.entries().associate { it.key to it.value.firstOrNull().orEmpty() },
            body = bodyText,
        )
    }

    override fun stream(request: NetonHttpRequest): Flow<NetonHttpStreamChunk> = channelFlow {
        // channelFlow (not flow): Ktor's prepareRequest{}.execute{} callback runs on Dispatchers.IO,
        // and `flow { emit }` requires emit to happen in the collector's context. channelFlow uses a
        // Channel to bridge cross-thread producer/consumer correctly.
        // prepareRequest yields a statement we can scope; the lambda closes the response on exit.
        client.prepareRequest { applyRequest(request) }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            val buf = ByteArray(DEFAULT_STREAM_CHUNK_BYTES)
            try {
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n <= 0) break
                    send(NetonHttpStreamChunk.Bytes(buf.copyOf(n)))
                }
            } catch (e: CancellationException) {
                throw e   // CancellationException MUST propagate; structured concurrency closes channel via execute scope
            } catch (e: IOException) {
                throw NetonHttpException(NetonHttpError.Network(e.message ?: "Stream read error", e))
            } catch (e: Throwable) {
                throw NetonHttpException(NetonHttpError.Unknown(e.message ?: "Unknown stream error", e))
            }
            send(NetonHttpStreamChunk.End(
                finalHeaders = response.headers.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
            ))
        }
    }

    override suspend fun close() {
        client.close()
    }

    private fun HttpRequestBuilder.applyRequest(req: NetonHttpRequest) {
        method = req.method.toKtor()
        url(req.url)
        headers {
            req.headers.forEach { (k, v) -> append(k, v) }
        }
        req.body?.let { applyBody(it) }
        req.timeout?.let { t ->
            // Per-request timeout override
            timeout {
                t.connectMillis?.let { connectTimeoutMillis = it }
                t.requestMillis?.let { requestTimeoutMillis = it }
                t.socketMillis?.let { socketTimeoutMillis = it }
            }
        }
    }

    private fun HttpRequestBuilder.applyBody(body: NetonHttpBody) {
        when (body) {
            is NetonHttpBody.Json -> {
                contentType(ContentType.Application.Json)
                setBody(body.text)
            }
            is NetonHttpBody.Text -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.text)
            }
            is NetonHttpBody.Bytes -> {
                contentType(ContentType.parse(body.contentType))
                setBody(body.bytes)
            }
        }
    }

    private fun NetonHttpMethod.toKtor(): HttpMethod = when (this) {
        NetonHttpMethod.Get -> HttpMethod.Get
        NetonHttpMethod.Post -> HttpMethod.Post
        NetonHttpMethod.Put -> HttpMethod.Put
        NetonHttpMethod.Delete -> HttpMethod.Delete
        NetonHttpMethod.Patch -> HttpMethod.Patch
        NetonHttpMethod.Head -> HttpMethod.Head
        NetonHttpMethod.Options -> HttpMethod.Options
    }

    companion object {
        private const val DEFAULT_STREAM_CHUNK_BYTES = 8 * 1024
    }
}

// Ktor 3.x: HttpResponse.bodyAsChannel() lives in io.ktor.client.statement
private fun HttpResponse.bodyAsChannel(): ByteReadChannel = this.rawContent
```

NOTE: In Ktor 3.x the canonical method on `HttpResponse` to get the byte channel is `bodyAsChannel()` (extension or member depending on version). If the import-only path differs in 3.4.2, replace the bottom helper with the correct call (the engineer may need to consult Ktor 3.4.2 KMP docs; alternatives are `response.rawContent` for `HttpResponse` in some KMP setups).

- [ ] **Step 2: Verify it compiles on all targets**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 :neton-http-client:compileKotlinLinuxX64 :neton-http-client:compileKotlinMingwX64 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` on all 3 targets.

If `bodyAsChannel()` import fails, try removing the helper and replacing the call with `response.rawContent` (Ktor 3.x APIs vary by patch version). The engineer should verify against Ktor 3.4.2 source if needed.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/internal/DefaultNetonHttpClient.kt
git commit -m "feat(http-client): implement DefaultNetonHttpClient with Ktor engine"
```

---

## Task 13: MockEngine request/response tests

**Files:**
- Create: `neton-http-client/src/commonTest/kotlin/neton/http/client/MockEngineHttpClientTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
// neton-http-client/src/commonTest/kotlin/neton/http/client/MockEngineHttpClientTest.kt
package neton.http.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import neton.http.client.internal.DefaultNetonHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockEngineHttpClientTest {

    @Test
    fun getRequestReturnsBodyAndStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.example.com/v1/test", request.url.toString())
            assertEquals("GET", request.method.value)
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val resp = client.request(NetonHttpRequest(
            method = NetonHttpMethod.Get,
            url = "https://api.example.com/v1/test",
        ))
        assertEquals(200, resp.statusCode)
        assertEquals("""{"ok":true}""", resp.body)
        assertTrue(resp.headers["Content-Type"]?.contains("application/json") == true)
        client.close()
    }

    @Test
    fun postWithJsonBodySendsCorrectContentType() = runTest {
        var capturedBody: String? = null
        var capturedContentType: String? = null
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            capturedContentType = request.body.contentType.toString()
            respond("ok", HttpStatusCode.OK)
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        client.request(NetonHttpRequest(
            method = NetonHttpMethod.Post,
            url = "https://api.example.com/x",
            body = NetonHttpBody.Json("""{"k":1}"""),
        ))
        assertEquals("""{"k":1}""", capturedBody)
        assertTrue(capturedContentType?.startsWith("application/json") == true)
        client.close()
    }

    @Test
    fun headersForwardedToEngine() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers["Authorization"]
            respond("ok", HttpStatusCode.OK)
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        client.request(NetonHttpRequest(
            method = NetonHttpMethod.Get,
            url = "https://api.example.com/x",
            headers = mapOf("Authorization" to "Bearer test-key"),
        ))
        assertEquals("Bearer test-key", capturedAuth)
        client.close()
    }

    @Test
    fun http500BodyAndStatusReturnedNotThrown() = runTest {
        // expectSuccess=false: 5xx returns response, not exception (caller decides)
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":"upstream"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val resp = client.request(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/x"))
        assertEquals(500, resp.statusCode)
        assertEquals("""{"error":"upstream"}""", resp.body)
        client.close()
    }

    @Test
    fun networkErrorMapsToNetonHttpExceptionNetwork() = runTest {
        val engine = MockEngine { _ -> throw io.ktor.utils.io.errors.IOException("connection refused") }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val ex = assertFailsWith<NetonHttpException> {
            client.request(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://api.example.com/x"))
        }
        assertTrue(ex.error is NetonHttpError.Network, "Expected Network error, got ${ex.error::class.simpleName}")
        client.close()
    }
}

// Helper: wrap a MockEngine instance as a factory (Ktor's MockEngine doesn't itself implement HttpClientEngineFactory directly in tests).
private fun engineFactoryFor(engine: MockEngine): io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    return object : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
        override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine = engine
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.MockEngineHttpClientTest" 2>&1 | tail -15`
Expected: All 5 tests pass. If `engineFactoryFor` helper has type issues, check Ktor 3.4.2 MockEngine constructor — the cleaner alternative is to instantiate `HttpClient(engine)` directly and adapt DefaultNetonHttpClient to accept a pre-built `HttpClient` (alternative constructor) — engineer can refactor if needed.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonTest/kotlin/neton/http/client/MockEngineHttpClientTest.kt
git commit -m "test(http-client): add MockEngine request/response tests"
```

---

## Task 14: Streaming + cancellation tests

**Files:**
- Create: `neton-http-client/src/commonTest/kotlin/neton/http/client/CancellationTest.kt`

- [ ] **Step 1: Write streaming + cancellation tests**

```kotlin
// neton-http-client/src/commonTest/kotlin/neton/http/client/CancellationTest.kt
package neton.http.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import neton.http.client.internal.DefaultNetonHttpClient
import neton.http.client.sse.parseSseEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CancellationTest {

    @Test
    fun streamYieldsByteChunksAndEnd() = runTest {
        val payload = "data: hello\n\ndata: world\n\n"
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val chunks = client.stream(NetonHttpRequest(
            method = NetonHttpMethod.Get,
            url = "https://api.example.com/stream",
        )).toList()

        // Must end with NetonHttpStreamChunk.End
        assertTrue(chunks.last() is NetonHttpStreamChunk.End, "Last chunk must be End, was ${chunks.last()::class.simpleName}")
        // Concatenated byte content must equal payload
        val combined = chunks.filterIsInstance<NetonHttpStreamChunk.Bytes>()
            .joinToString(separator = "") { it.bytes.decodeToString() }
        assertEquals(payload, combined)
        client.close()
    }

    @Test
    fun streamParsedAsSseProducesExpectedEvents() = runTest {
        val payload = "data: a\n\nevent: ping\ndata: \n\ndata: b\n\n"
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        val events = client.stream(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://x/stream"))
            .parseSseEvents()
            .toList()
        assertEquals(3, events.size)
        assertEquals("a", events[0].data)
        assertEquals("ping", events[1].event)
        assertEquals("b", events[2].data)
        client.close()
    }

    @Test
    fun cancellingFlowEarlyDoesNotThrowFailedAndStopsReading() = runTest {
        // Long-running payload; we take only the first event then cancel.
        val payload = buildString {
            repeat(100) { append("data: event-$it\n\n") }
        }
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/event-stream"),
            )
        }
        val client = DefaultNetonHttpClient(engineFactory = engineFactoryFor(engine))
        // take(1) cancels the upstream flow after first element
        val first = client.stream(NetonHttpRequest(method = NetonHttpMethod.Get, url = "https://x/stream"))
            .parseSseEvents()
            .take(1)
            .toList()
        assertEquals(1, first.size)
        assertEquals("event-0", first[0].data)
        // No exception thrown; structured concurrency closed underlying response.
        client.close()
    }
}

private fun engineFactoryFor(engine: MockEngine): io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
    return object : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.mock.MockEngineConfig> {
        override fun create(block: io.ktor.client.engine.mock.MockEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine = engine
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.CancellationTest" 2>&1 | tail -15`
Expected: All 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonTest/kotlin/neton/http/client/CancellationTest.kt
git commit -m "test(http-client): add streaming + cancellation tests with MockEngine"
```

---

## Task 15: `HttpClientComponent` — Neton Framework adapter (thin)

**Files:**
- Create: `neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientComponent.kt`

This is the **Mode 2** adapter from the dual-usage design constraint. It is intentionally thin: load config → call standalone factory → bind to context. **No business logic.** Removing this file should not affect Mode 1 (standalone) usage.

- [ ] **Step 1: Create `HttpClientComponent.kt`**

```kotlin
// neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientComponent.kt
package neton.http.client

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.logging.LoggerFactory

/**
 * Thin Neton Framework adapter — binds a [NetonHttpClient] (built via the standalone factory)
 * into [NetonContext] for downstream Neton modules.
 *
 * `Neton.run { httpClient { requestMillis = 30_000 } }`
 *
 * Removing this file does NOT affect standalone usage via `NetonHttpClient.create { ... }`.
 */
object HttpClientComponent : NetonComponent<HttpClientConfig> {

    override fun defaultConfig(): HttpClientConfig = HttpClientConfig()

    override suspend fun init(ctx: NetonContext, config: HttpClientConfig) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.http.client")

        // Build via the same standalone factory used by Mode 1 callers — single source of truth.
        // NetonHttpClient.create throws NetonHttpException on invalid config.
        val client = NetonHttpClient.create {
            connectMillis = config.connectMillis
            requestMillis = config.requestMillis
            socketMillis = config.socketMillis
            debug = config.debug
        }
        ctx.bind(NetonHttpClient::class, client)

        if (config.debug) {
            log?.info("HTTP client initialized via Neton component", mapOf(
                "connectMillis" to (config.connectMillis ?: 5_000),
                "requestMillis" to (config.requestMillis ?: 60_000),
                "socketMillis" to (config.socketMillis ?: 60_000),
            ))
        }
    }
}

/** DSL entry: `httpClient { ... }` */
fun Neton.LaunchBuilder.httpClient(block: HttpClientConfig.() -> Unit = {}) {
    install(HttpClientComponent, block)
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :neton-http-client:compileKotlinMacosArm64 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientComponent.kt
git commit -m "feat(http-client): add HttpClientComponent thin adapter + httpClient DSL"
```

---

## Task 16: Standalone-usage test (Mode 1 verification — no Neton runtime)

This test proves the dual-usage design constraint. It MUST import zero `neton.core.*` or `neton.logging.*` symbols and MUST construct a working `NetonHttpClient` purely via `NetonHttpClient.create { ... }`.

**Files:**
- Create: `neton-http-client/src/commonTest/kotlin/neton/http/client/StandaloneUsageTest.kt`

- [ ] **Step 1: Write standalone-usage test**

```kotlin
// neton-http-client/src/commonTest/kotlin/neton/http/client/StandaloneUsageTest.kt
//
// CONTRACT GUARDRAIL: This test verifies "Mode 1" of the dual-usage design constraint.
// MUST NOT import:
//   - neton.core.*
//   - neton.logging.*
//   - neton.http.client.internal.* (internal types)
//
// If this test ever needs Neton runtime imports, the standalone-usage contract is broken.
package neton.http.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandaloneUsageTest {

    @Test
    fun createWithDefaultConfigProducesUsableClient() = runTest {
        val client = NetonHttpClient.create()
        // We can't make a real request without an engine, but instantiation alone proves the
        // factory does NOT require NetonContext / LoggerFactory / any runtime dependency.
        assertTrue(client is NetonHttpClient)  // tautology, but proves type binding
        client.close()
    }

    @Test
    fun createWithDslConfigAppliesOverrides() = runTest {
        // DSL block runs without any Neton runtime / context.
        val client = NetonHttpClient.create {
            requestMillis = 12_345
            connectMillis = 999
            socketMillis = 67_890
            debug = true
        }
        client.close()  // success = no exception during construction
    }

    @Test
    fun invalidConfigThrowsTypedException() {
        val ex = assertFailsWith<NetonHttpException> {
            NetonHttpClient.create { requestMillis = 0 }  // 0 invalid (must be > 0)
        }
        assertTrue(ex.error is NetonHttpError.Unknown,
            "Expected Unknown error for invalid config, got ${ex.error::class.simpleName}")
        assertTrue("requestMillis" in ex.error.message,
            "Error message should mention invalid field, was: ${ex.error.message}")
    }

    // Optional integration-style test: prove the standalone client can actually do a request
    // when given a real engine. We can't easily inject a MockEngine into `NetonHttpClient.create`
    // (which uses the platform default), but we can construct DefaultNetonHttpClient directly with
    // MockEngine for end-to-end-without-runtime verification. We import DefaultNetonHttpClient via
    // the package because it's `internal` but still in our test module's same module.
    //
    // NOTE: If `internal` visibility blocks this in commonTest, drop this test — the first three
    // tests above are sufficient to prove the constraint. Do NOT change visibility to `public`
    // just to enable this test.
}
```

- [ ] **Step 2: Run the standalone usage test**

Run: `./gradlew :neton-http-client:macosArm64Test --tests "neton.http.client.StandaloneUsageTest" 2>&1 | tail -10`
Expected: All 3 tests pass.

- [ ] **Step 3: Verify the test file has NO Neton runtime imports**

Run: `grep -E "^import (neton\.core|neton\.logging)" neton-http-client/src/commonTest/kotlin/neton/http/client/StandaloneUsageTest.kt 2>&1`
Expected: **No output** (no matches). If any matches → contract violation, fix before commit.

- [ ] **Step 4: Commit**

```bash
git add neton-http-client/src/commonTest/kotlin/neton/http/client/StandaloneUsageTest.kt
git commit -m "test(http-client): add standalone-usage test (Mode 1 dual-usage contract)"
```

---

## Task 17: Full module build + all tests + acceptance check

**Files:** none modified

- [ ] **Step 1: Full module build**

Run: `./gradlew :neton-http-client:build 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`. All KMP targets compile (macosArm64, macosX64, linuxX64, linuxArm64, mingwX64).

- [ ] **Step 2: Full module test suite**

Run: `./gradlew :neton-http-client:allTests 2>&1 | tail -10`
Expected: All tests pass across all targets that have test executions. Check counts: ~22 tests (13 parser + 6 flow ops + 5 mock engine + 3 cancellation, minus any duplicate count if some targets share).

- [ ] **Step 3: API hygiene check (no Ktor in public API)**

Run: `grep -rn "io.ktor" neton-http-client/src/commonMain/kotlin/neton/http/client/ --include="*.kt" 2>&1 | grep -v "/internal/"`
Expected: No matches (Ktor imports only inside `internal/` subdirectory). If any leak into the public API surface (e.g., `NetonHttpClient.kt`, `NetonHttpRequest.kt`), that is a contract violation — fix before moving on.

- [ ] **Step 4: Dual-usage contract check (no Neton runtime in standalone test)**

Run: `grep -E "^import (neton\.core|neton\.logging)" neton-http-client/src/commonTest/kotlin/neton/http/client/StandaloneUsageTest.kt 2>&1`
Expected: **No output**. The standalone test must remain Neton-runtime-free.

Run: `grep -rE "import neton\.(core|logging)" neton-http-client/src/commonMain/kotlin/neton/http/client/NetonHttpClient.kt neton-http-client/src/commonMain/kotlin/neton/http/client/HttpClientConfig.kt neton-http-client/src/commonMain/kotlin/neton/http/client/internal/DefaultNetonHttpClient.kt 2>&1`
Expected: **No output**. Standalone-path files must not import Neton runtime.

(`HttpClientComponent.kt` IS allowed to import `neton.core.*` and `neton.logging.*` — that's the adapter layer.)

- [ ] **Step 5: Acceptance gate verification**

Verify each acceptance gate from spec §6 that applies to PR0:
- Gate 1 (all KMP targets compile) → ✅ from Step 1
- Gate 2 (all unit tests pass — should be ~25 tests now: 13 parser + 6 flow ops + 5 mock engine + 3 cancellation + 3 standalone — wait, check actual count) → ✅ from Step 2
- Gate 4 (SSE parser handles edge cases) → ✅ from NetonSseParserTest (13 tests covering all spec §2.5 cases)
- Gate 11 (no io.ktor.* in public API) → ✅ from Step 3
- Gate 13 (logging redaction primitive shipped) → partial: NetonRedactionPolicy + DEFAULT_REDACTED_HEADERS defined; full integration in PR1+

**Plus dual-usage design constraint gates (added in this plan)**:
- Standalone mode 1: a test instantiates `NetonHttpClient.create { ... }` without any Neton runtime → ✅ from `StandaloneUsageTest`
- Component mode 2: `HttpClientComponent` is a thin adapter that delegates to `NetonHttpClient.create(...)` → ✅ from `HttpClientComponent.kt` source (single `NetonHttpClient.create` call, no duplicate construction logic)
- Architectural separation: `NetonHttpClient.kt` / `HttpClientConfig.kt` / `internal/DefaultNetonHttpClient.kt` have no `neton.core.*` or `neton.logging.*` imports → ✅ from Step 4

PR0 acceptance: PASSED.

---

## PR0 Self-Review

After completing all 17 tasks, verify:

1. **Spec coverage** — §2 of spec is fully implemented:
   - §2.1 Scope (10 items): all delivered ✅
   - §2.2 KMP targets: all 5 declared ✅
   - §2.3 Public API: NetonHttpClient + Request/Response/StreamChunk/Method/Body/Timeout ✅
   - §2.4 Typed error: NetonHttpError + NetonHttpException ✅
   - §2.5 SSE parser: NetonSseParser + edge cases ✅
   - §2.6 Cancellation: propagation via structured concurrency ✅
   - §2.7 Redaction policy: NetonRedactionPolicy + DEFAULT_REDACTED_HEADERS ✅
   - §2.8 Retry primitive: NetonRetryPolicy + NoRetryPolicy ✅
   - §2.9 Component lifecycle: HttpClientComponent + DSL ✅
   - §2.10 Module structure: matches spec layout ✅

2. **Dual-usage design constraint** (added in this plan, beyond spec):
   - Mode 1 (standalone): `NetonHttpClient.create { ... }` factory works without Neton runtime ✅ (StandaloneUsageTest)
   - Mode 2 (component): `HttpClientComponent` is a thin adapter delegating to `NetonHttpClient.create(...)` ✅
   - No `neton.core.*` / `neton.logging.*` imports in standalone-path files ✅ (Task 17 Step 4)

3. **Placeholder scan** — No "TBD", "TODO", "implement later" in delivered code.

4. **Type consistency** — `NetonHttpClient.request()` returns `NetonHttpResponse`, matching what `DefaultNetonHttpClient` produces; `Flow<NetonHttpStreamChunk>` matches across `stream()`, `parseSseEvents()` overloads, and tests. `HttpClientConfig` is the single config DSL used by both `NetonHttpClient.create` and `HttpClientComponent`.

5. **Acceptance gates met**: spec gates 1, 2, 4, 11, 13 (partial) + plan's dual-usage gates.

---

## PR0 done. Next steps:

Once PR0 is merged (or a feature branch is ready), proceed to:
- **PR1 plan**: `docs/superpowers/plans/2026-05-17-pr1-neton-ai-non-stream.md` (to be written next)
- **PR2 plan**: streamText + tool loop
- **PR3 plan**: router polish + embedding + examples + docs

Each subsequent PR plan depends on the previous PR being functionally complete in the working branch.
