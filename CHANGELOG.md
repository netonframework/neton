# Changelog

All notable changes to Neton are documented here.

## 1.0.0-beta8

### Changed

- **Fewer allocations on the request path.** Two of them showed up in a flame graph of the
  HTTP Arena baseline; both are on the path every request takes.

  - `percentDecode` allocated an `ArrayList<Byte>` — one box per byte — and, for every
    ordinary character, a one-character `String` and a `ByteArray` to append it. A query
    like `?a=3&b=4` went through it four times, and nothing in it needs decoding. It now
    scans for `%` or `+` first and returns the same string when there is neither, and
    decodes over bytes rather than boxes when there is.

  - The parsed request-header map was built for every request. Dispatch reads one header
    on the way in (`X-Request-Id`), and the full map is only needed by the access log, the
    security pipeline, and a handler that reads headers itself — none of which most
    requests reach. `BufferedHttpRequest` now takes the block unparsed, with a
    transport-supplied single-header lookup; the map is materialised on first real use.
    On hyper4k that lookup scans the raw bytes and parses nothing.

  Measured on the arena entry (macOS/arm64, `wrk -t4 -c128`, eight interleaved runs):
  about +10% on baseline, winning five to seven rounds out of eight. The machine cannot
  resolve smaller differences than that.

  Requires `com.netonstream:hyper4k:0.5.0`.

## 1.0.0-beta7

### Fixed

- **Responses could be dropped, or delivered to the wrong request.** The engine has a fast path
  that lets a handler finishing inside the callback hand its response straight back instead of
  waking a channel, and it was gated on a thread-local *flag*. Any delivery that happened to run
  on that thread while the flag was set took the slot, whoever it belonged to: a coroutine
  resuming there for another request either lost its response — the caller discarded the failure
  return — or had it collected as the answer to the request being served.

  It went unnoticed because until 1.0.0-beta6 a normal response never used that path; it
  committed through the streaming channel. Writing complete responses inline put every request
  on it. The symptom in the HTTP Arena run was 78 client reconnects and a p99 of 27 ms on the
  otherwise idle latency-10k profile, against 288 µs before.

  The flag now carries the responder it belongs to, so the fast path is only taken for that
  request and everything else goes through its own channel. A delivery that still cannot be
  handed over is counted in `droppedResponses` rather than discarded — any value above zero is
  a bug, and it is now visible as a number instead of as unexplained tail latency.

  Requires `com.netonstream:hyper4k:0.4.0`.

## 1.0.0-beta6

### Changed

- **A complete response is written on the thread that already has the request.**
  `response.text()` / `response.json()` used to commit through hyper4k's response channel,
  and every channel write crosses to the engine's blocking write pool — 32 threads, shared
  by the whole process. A two-byte `"ok"` paid a thread hop and was capped at that pool's
  width. Only `stream { }` needs the channel; a complete body now goes back as one buffered
  response and the engine writes it inline.

  Measured on the HTTP Arena entry (macOS/arm64, `wrk -t4 -c128`, interleaved runs, medians):
  **50.1k to 102.7k req/s**, and the fixed build ran past a ceiling the previous one could
  not reach at all.

  Two things follow from the same change:

  - Responses with a known length now carry `Content-Length` instead of being chunked.
  - The engine's two cheap-request optimisations start working. `CoroutineStart.UNDISPATCHED`
    and the timeout that is only armed once a handler suspends were both dead: the channel
    write suspended on every request, so nothing ever completed inline. That is why disabling
    the request timeout used to look like a large win on its own — it was paying for a timer
    that only existed because of this hop.

  A redirect is a complete response too, so it no longer opens the channel either.

### Fixed

- Nothing in the request contract changes. The buffered and live paths already had to agree,
  and the conformance suites for both engines pass unchanged; the new
  `aCompleteBodyNeverOpensTheChannel` test pins the contract this release depends on.

## 1.0.0-beta5

### Changed

- **Fewer allocations per request on the buffered dispatch path.** Measured on the HTTP Arena
  entry (macOS/arm64, `wrk -t4 -c128`, medians of interleaved runs): 42.3k to 44.0k req/s, about
  +4%. Nothing about the request contract changes.
  - `BufferedHttpRequestView` used `by lazy(PUBLICATION)` for seven properties. Each one
    allocates a `Lazy` wrapper per request and reads it atomically, on an object that lives for
    one request and is touched by one coroutine. They are plain memoised fields now; the worst
    case is repeating a cheap computation instead of paying an allocation every time.
  - The access log built its eight-entry field map — and boxed four numbers — before calling
    `info()`, where the level filter lives. Under `level = "WARN"` every request paid for a line
    that was then discarded.
  - The logger and the CORS config were looked up in `NetonContext` on every request; both are
    resolved once in `bind()`.
  - The exact-route index was keyed by `"GET /path"`, so every lookup allocated its own key. It
    is keyed per method now.
  - `pathParams` and `attributes` were built eagerly for every request, including the routes and
    requests that never touch them.
  - `requestTraceId()` read the clock a second time and allocated an `IntRange` per request to
    draw a random number.

- **`Logger` gained `isEnabled(level)`** with a default of `true`, so callers can skip building
  fields for a line that will be filtered. Existing implementations are unaffected.

- The hyper4k engine moves to `com.netonstream:hyper4k:0.3.0`, which allows a request timeout of
  `0` to disable the per-request timeout wrapper. That wrapper costs two coroutine objects and
  their `JobSupport` state transitions on every request — about 19% of throughput in the same
  measurement. The default is unchanged at 30s; disabling it is a deployment choice for services
  that already have a timeout in front of them.

## 1.0.0-beta4

### Added

- **An outbound HTTP client on hyper4k.** `HttpClient.create { }` is served by
  `neton-http-hyper4k`, so a single `com.netonstream:neton` dependency now covers both
  directions: the server runs on hyper4k and so does the client. Server and client share one
  engine, and nothing pulls Ktor into a minimal application any more.

- **A shared client conformance suite** (`neton.http.conformance.HttpClientConformanceSuite`)
  with a scripted origin, run against the hyper4k client and both Ktor clients — 13 cases each,
  covering status/header/body fidelity, non-success handling, connection refusal, timeouts,
  streaming, flow cancellation and proxying.

- **A client capability model.** An engine declares what it supports; a capability whose
  conformance case is not overridden fails rather than silently passing. The hyper4k client
  declares `STREAMING_BODY`, `CANCELLATION` and `PROXY`.

- **`HttpClientProvider`** for modules that must build one client per runtime configuration
  (an AI gateway with per-channel proxies, for example) without depending on an engine
  themselves.

### Changed

- The hyper4k engine moves to `com.netonstream:hyper4k:0.2.0`, which adds the outbound client
  and HTTP proxy support (ABI 4.1).
- S3 storage goes through the borrowed `HttpClient` instead of its own Ktor engine.
- `neton-http-ktor` leaves the BOM. Choosing the maintenance engine is now a deliberate,
  versioned decision.

### Fixed

- **A missing engine is a readable compile error instead of `Unresolved reference`.** Both
  `http { }` and `HttpClient.create { }` have fallback overloads in the contract layer that
  name the dependency to add.
- **The startup banner and the `modules.loaded` log reported `1.0.0-beta1`** in every release
  since. `Neton.VERSION` was a hand-written constant nobody bumped; it is generated from the
  Gradle project version now, so it cannot drift again.
- **`./gradlew build` failed on the shared `posixMain` metadata compilation.** The scripted
  conformance origin referenced `sa_family_t` and `timeval` directly, and both differ in width
  and layout between macOS and Linux. They move behind `expect`/`actual` like `disableSigpipe`.
  Target-specific test tasks never compile that source set, which is why it passed locally.
- `com.netonstream:neton`'s POM description on Maven Central is the real project description
  rather than a placeholder.

## 1.0.0-beta3

### Added

- **`com.netonstream:neton` now ships an HTTP engine.** The aggregate pulled in `neton-http`,
  which is the abstraction only, so a one-line dependency did not actually compile: `http { }`
  is an extension supplied by an engine module and no engine was on the classpath. It now
  depends on `neton-http-hyper4k`, the Rust engine, so

  ```kotlin
  implementation("com.netonstream:neton:1.0.0-beta3")
  ```

  is enough to start a server.

- **Windows (`mingwX64`) support for the hyper4k engine.** `neton-http-hyper4k` had dropped the
  Windows target; it is back, so all five targets of the aggregate — macOS arm64/x64, Linux
  x64/arm64 and Windows x64 — carry an engine.

### Fixed

- **The startup banner and the `modules.loaded` log reported `1.0.0-beta1` in every release
  since.** `Neton.VERSION` was a hand-written constant and nobody bumped it. It is now generated
  from the Gradle project version, so it cannot drift again. The fix lands after 1.0.0-beta3 was
  published, so beta3 binaries still print `1.0.0-beta1`.

### Changed

- The hyper4k engine is resolved from Maven Central (`com.netonstream:hyper4k:0.1.1`) rather
  than from a sibling checkout. Build `hyper4k` from source with `-Phyper4k.local=true`.

## 1.0.0-beta2

### Fixed

- **`@Controller` routes were never registered in applications built against the published
  artifacts.** The framework called a stub `neton.core.generated.GeneratedInitializer` inside
  `neton-core` and relied on the application's KSP-generated object of the same name
  shadowing it at link time. That happens to work for source (project) dependencies and
  does not work for klibs resolved from Maven: the call binds to the stub, the generated
  routes are dead code, and every controller endpoint answers 404 with no error anywhere.
  Every application in this repository uses project dependencies, which is why it went
  unnoticed until the first standalone application was built against 1.0.0-beta1.

  Registration is now explicit. KSP generates `GeneratedInitializer` as a
  `neton.core.module.ModuleInitializer` and the application passes it in:

  ```kotlin
  Neton.run(args) {
      routing { }
      modules(GeneratedInitializer)   // neton.core.generated.GeneratedInitializer
  }
  ```

  Applications that already use module manifests (`modules(*GeneratedApplicationModules.modules)`)
  are unaffected. Startup now logs `routing.no_routes` when `routing { }` is installed but
  nothing registered a route, which is the symptom of forgetting the line above.

### Changed

- The `neton.core.generated.GeneratedInitializer` stub was removed from `neton-core`; the
  name is now owned entirely by the application's generated code.

## 1.0.0-beta1

First public beta. The framework core (HTTP, routing, security, database, logging,
KSP codegen) is exercised by a real multi-module backend and its admin frontends,
so this release focuses on hardening the public surface rather than adding features.

### Fixed

- Publishing collapsed every Kotlin/Native target publication onto the root artifact id, so a
  full publish overwrote `neton-core` with whichever target was published last and the root
  Gradle module metadata lost its `available-at` links. Target publications keep their own
  artifact ids again.
- The MinGW C bridge (`posixenv`) can now be cross-compiled on macOS and Linux with `clang`
  and the msys2 sysroot that Kotlin/Native already downloads, so a single host can produce
  every target and the root publication. Windows still builds natively.
- **`@RateLimit` never limited anything.** The only enforcement point lived in
  `DefaultRequestEngine.processRequest`, which the HTTP adapter never calls — it
  dispatches the KSP-generated `RouteDefinition.handler` directly. The annotation,
  its KSP metadata, the interceptor and the Redis/local stores all existed while no
  request was ever checked. Enforcement now runs on the live dispatch path through
  the new `RateLimitGate` core interface, after authentication and before the handler.
  Anyone relying on `@RateLimit` for brute-force protection was unprotected before
  this release.
- **`@Cacheable` / `@CachePut` / `@CacheEvict` could not be used at all.** The
  annotations, the KSP weaving and the L1+L2 implementation shipped, but nothing ever
  bound a `CacheManager`: there was no `CacheComponent` and no `cache { }` DSL, so the
  generated code failed and pointed at a DSL that did not exist. `@CacheEvict` also
  generated `getCache<Any?>(...)`, which never compiled.
- **`redis.conf` values were silently discarded.** `keyPrefix` was omitted from the
  DSL/file merge entirely; `port` used a condition that ignored the file value; and
  `host` was compared with `ifBlank`, which never matches because the default host is
  not blank. Configuring any of the three only in `redis.conf` had no effect.
- **Cache and lock keys could collide across different requests.** The generated key
  expression reads `HandlerArgs`, which holds path and query values only. Body, header,
  cookie, form and injected parameters all resolved to `null`, so two requests differing
  only in those would share one key and `@Cacheable` could return one caller's response
  to another. Key templates also silently resolved unknown placeholders to an empty
  string, so the documented `{user.id}` form produced a constant key. Keys also used the
  Kotlin parameter name while binding uses the alias, so `@PathVariable("id") userId`
  made `{userId}` resolve to nothing and `{id}` — the name actually present at runtime —
  fail validation. All of these are now compile errors naming the offending parameter and
  the usable alternatives.
- **`@Cacheable` / `@Lock` on a non-route method compiled but never ran.** Weaving happens
  in the generated route handler, so these annotations were silently dropped both on Logic
  or service classes and on plain helper methods inside a `@Controller`. KSP now rejects
  both, pointing at `LockManager.withLock` / `CacheManager` for explicit use.
- **`redis { }` could not override `redis.conf`.** The merge inferred "the DSL set this"
  by comparing against the default value, so `redis { port = 6379 }` lost to a file
  setting 6380, and `debug` was combined with `or` — `debug = false` could not turn off
  a file's `debug = true`. Both layers now model "unset" as `null` (`RedisSettings`),
  giving a real DSL > file > default precedence. `password` is nullable in its own right,
  so it additionally tracks assignment and `redis { password = null }` clears a file value.
- Startup called `RequestEngine.setAuthenticationContext` on every boot into a method
  whose only implementation was an empty body.

### Changed

- `HmacSha256` moved from `neton.security.internal` to `neton.security.crypto` and is
  now public, alongside `Sha256`, `SecretBox` and `PasswordHasher`. `signForPassword`
  is folded into `sign`. Downstream code no longer has to import an internal package
  to sign a request.
- `CacheManager` gained type-agnostic `evict(name, key)` / `evictAll(name)`. L1 is
  sharded per value type while L2 is keyed by cache name, so evicting through one type
  would otherwise clear L2 and leave another type's L1 entry stale.
- `RequestEngine` is now a route registry: `processRequest` and
  `setAuthenticationContext` were removed from the interface. Request dispatch belongs
  to the HTTP adapter.
- `@Cacheable` / `@CachePut` on a `Unit`- or `Nothing`-returning function is a compile
  error rather than a warning, matching the cache spec.
- `redis { }` now takes a `RedisSettings` receiver (nullable fields) instead of
  `RedisConfig`. Existing `redis { host = "..."; port = ... }` blocks are unaffected;
  `RedisConfig` remains the resolved runtime type.

### Removed

- The unreachable dispatch chain in `neton-routing` (route matching, parameter
  binding, response serialization, error mapping). It carried a pattern validator that
  always returned true, a body binder that threw for any non-`String` type, hand-rolled
  JSON that did not escape keys, and demo strings in a fallback handler.
- `MockSecurityBuilder` (accepted authenticator registrations, then reported security
  as disabled — fail-open), `MockHttpAdapter` (reported a successful start without
  listening on a port) and `MockRedisConnection` (in-memory fake Redis). All three were
  public and unused. `MockRequestEngine` remains for lifecycle tests.
- The empty `neton-http-hyper4k/` directory, which was never part of the build.

### Added

- A single entry coordinate, `com.netonstream:neton`, that depends on `neton-core`,
  `neton-logging`, `neton-http` and `neton-routing` and exports `neton-bom` constraints. One
  versioned line gives a runnable service; every other module is then added without a
  version and resolves to the same release. `neton-bom` is also published on its own.
- `cache { }` DSL and `config/cache.conf` (`[caches.<name>]`) with fail-fast parsing:
  unknown codec, non-numeric `ttlMs`/`maxSize` and non-boolean flags are rejected
  rather than silently replaced by defaults. Installing `cache { }` without `redis { }`
  fails at startup instead of degrading to an L1-only cache that behaves differently
  per instance.
- `examples/cache-demo`, covering all three cache annotations.
- Concurrency-safe write primitives in `neton-database`, replacing hand-written SQL
  for cases where read-modify-write is incorrect rather than merely slow:
  - `UpdateScope.increment(prop, delta)` / `decrement(prop, delta)` render
    `col = col + ?` (delta bound as a parameter). Combined with `where { }` this is a
    CAS — a guard that does not hold yields an affected-row count of `0`. Covers
    counter bumps and balance deduction; guards that compare two columns are still
    out of reach (see Known gaps).
- Tests for previously untested areas. `neton-ksp` gets its first: 22 unit tests for key
  source resolution plus 15 that run `kotlinc` + KSP over fixtures and assert the
  diagnostics actually fail the build and that the generated handler hashes the binding
  name. Elsewhere: rate-limit enforcement (8), cache DSL and config parsing (10), the
  Redis three-layer config precedence (18, replacing an empty placeholder test file), and
  the atomic UPDATE primitives (8).

### Platform support

| Module | macosArm64 | macosX64 | linuxX64 | linuxArm64 | mingwX64 |
|---|---|---|---|---|---|
| neton-core, -logging, -http, -routing, -security, -redis, -cache, -validation, -storage, -jobs, -ai | ✅ | ✅ | ✅ | ✅ | ✅ |
| neton-database | ✅ | ❌ | ✅ | ✅ | ✅ |
| neton-ksp | JVM (compiler plugin) | | | | |

`neton-database` has no `macosX64` target because its driver, sqlx4k, publishes no
`macosX64` artifact. Intel Macs can use every other module; database work needs Apple
Silicon, Linux or Windows. As a consequence `./gradlew allTests` fails while resolving
`examples/backend-app` for `macosX64` — run `./gradlew macosArm64Test` on Apple Silicon.

### Known gaps

- There is no idempotent-insert API. `insertOrIgnore` was implemented and then withheld
  from this release: `ON CONFLICT DO NOTHING` expresses it exactly on PostgreSQL and
  SQLite, but MySQL has no equivalent that both avoids swallowing non-duplicate errors
  and reports its outcome unambiguously — `ON DUPLICATE KEY UPDATE` signals through
  affected rows, which `CLIENT_FOUND_ROWS` reports as `1` for a duplicate too. Shipping it
  on two of three dialects would freeze a partial method into the 1.0 ABI, so it waits for
  an integration test against a real MySQL server. Use raw SQL through `DbContext` for now.
- KSP diagnostics are covered by compile fixtures, but the wider generated surface
  (routing, entity tables, logic wiring) still has no generated-code assertions.
- `@Cacheable` / `@Lock` are woven only into `@Controller` route handlers. This is now
  rejected at compile time rather than ignored, but the annotations still cannot be used
  on a Logic or service class.
- Cache and lock keys can only be built from path and query parameters. Handlers whose
  identity depends on the request body must cache inside the Logic layer instead.
- `@Logic` skips constructor parameters that have default values. An optional dependency
  written as `redis: RedisClient? = null` is never injected, even when the binding
  exists.
- The database DSL cannot express aggregate projections, `SELECT … FOR UPDATE`, sequence
  reads, or `UPSERT` variants that update the conflicting row (`DO UPDATE SET`). Its
  predicates also compare a column against a bound value only, never against another
  column, so a guard such as `used_count < max_uses` still needs raw SQL through
  `DbContext`. Atomic increment and conflict-skipping insert are covered — see Added.
- The TOML parser does not support arrays, so list-valued config (for example CORS
  origins) must be set through the DSL.
