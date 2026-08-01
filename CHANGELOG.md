# Changelog

All notable changes to Neton are documented here.

## 1.0.0-beta1

First public beta. The framework core (HTTP, routing, security, database, logging,
KSP codegen) is exercised by a real multi-module backend and its admin frontends,
so this release focuses on hardening the public surface rather than adding features.

### Fixed

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
