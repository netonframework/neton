# Neton

**High-performance Kotlin/Native Web Framework**

Native-first, zero reflection, compile-time code generation, structured logging, engineering-grade DSL

[中文文档](README.zh-CN.md)

## AI-assisted development

Install the [Neton development skill from neton-docs](https://github.com/netonframework/netonframework.github.io/tree/main/skills)
for version-aware framework development and integration. Skills and specifications
are maintained centrally; adding a Gradle dependency does not install a skill.
[AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) retain repository entry points.
Forks still need project-specific requirements and acceptance tests.

## Building This Development Branch

The source-integration build uses sibling protocol repositories, not Maven Local.
For a fresh checkout, fetch the exact CI revisions first (existing sibling checkouts
are verified, never reset):

```bash
bash tools/ci/checkout-protocols.sh
./gradlew -Pnetonstream.local=true -Pnetonstream.local.openssl=true -PnativeTargets=all \
  :neton-core:macosArm64Test :neton-http:macosArm64Test :neton-websocket:macosArm64Test
```

Use `linuxX64Test` instead on an x64 Linux host. To avoid existing sibling checkouts,
set `NETON_PROTOCOL_SOURCES_DIR` for the checkout script and pass the same absolute
directory as `-Pnetonstream.sourcesDir=...` to Gradle. Foundation-only checks can use
`-Pnetonstream.local=false`. Releases must use published dependencies, not these
source substitutions; see [RELEASING.md](RELEASING.md).

---

## Features

Neton is a production-ready, engineering-focused web framework that differs from traditional JVM frameworks (Spring Boot, Ktor):

- **Native-first**: Compiles to native executables with fast startup and low resource usage
- **Zero reflection**: All routes/tables/fields are generated at compile time via KSP
- **Unified configuration**: TOML-based config with priority: CLI/ENV > environment conf > defaults
- **Structured logging**: Built-in multi-sink, async writing, WARN/ERROR guaranteed delivery
- **Security**: JWT authentication with composable Guard/Authenticator
- **Clear database semantics**: Entity (data class) + Table (single-table CRUD) + Logic (business aggregation)
- **Contract tests**: Core behaviors locked down via contract tests

---

## Framework Comparison

| Dimension | Neton (Kotlin/Native) | Spring Boot (Java) | Rust (Actix/Axum) | Go (Gin/Fiber) | Node.js (Express/Nest) |
|-----------|------------------------|---------------------|-------------------|----------------|------------------------|
| Runtime | Native executable | JVM | Native executable | Native executable | V8 + Node runtime |
| Engineering | High | High | Medium | Low | High |
| Dev efficiency | High | High | Low | High | High |
| Debug efficiency | High | High | Low | Fair | Fair |
| Startup time | Milliseconds | Seconds | Milliseconds | Milliseconds | Sub-second |
| Memory (100 conns) | ~20MB | 400MB+ | 10~30MB | 15~40MB | 100MB+ |
| Reflection | None | Heavy | None | None | None |
| Compile-time codegen | KSP routes/Table/security | Runtime scanning | None | None | None |
| Architecture | Core/Adapter/Entity/Table/Logic layers | IoC container driven | Library composition | Library composition | Middleware composition |
| Maintainability | API Freeze + Contract Tests | Mature but large | Strongly typed but scattered | Simple but loose | Ecosystem dependent |
| Extensibility | Adapter-based (DB/Redis/HTTP swappable) | Mature ecosystem | Highly customizable | Medium | Plugin dependent |
| Configuration | Unified TOML + CLI/ENV priority | YAML + Profiles | Manual | Manual | JSON/YAML |
| Logging | Built-in multi-sink + async + contract | Logback dependent | Crate dependent | Library dependent | Third-party |
| Security model | Identity freeze + JWT contract | Spring Security (manual assembly) | Manual assembly | Third-party | Third-party |
| Type-safe DSL | Kotlin strongly-typed DSL | Annotation-driven | Builder/functional | Middleware chain | Middleware chain |

---

## Supported Platforms

| Platform | Target | Status |
|----------|--------|--------|
| macOS ARM64 | `macosArm64` | Supported |
| macOS x64 | `macosX64` | Supported, except `neton-database` |
| Linux x64 | `linuxX64` | Supported |
| Linux ARM64 | `linuxArm64` | Supported |
| Windows x64 | `mingwX64` | Supported |

`neton-database` has no `macosX64` target because its driver, sqlx4k, publishes no
`macosX64` artifact. Every other module builds on Intel Macs; database work needs
Apple Silicon, Linux or Windows.

---

## Quick Start

### Dependency

```kotlin
implementation("com.netonstream:neton:1.0.0-beta1")
```

`neton` brings in `neton-core`, `neton-logging`, `neton-http` and `neton-routing` — enough for a
running service — and pins every other module to the same release, so optional modules are added
without a version:

```kotlin
implementation("com.netonstream:neton-database")
implementation("com.netonstream:neton-redis")
```

Projects that only want the version constraints can use
`implementation(platform("com.netonstream:neton-bom:1.0.0-beta1"))`.

### Minimal Example

```kotlin
Neton.run(args) {

    http {
        port = 8080
    }

    routing {
        get("/") {
            "Hello Neton!"
        }
    }
}
```

---

## Highlights

- Works without reflection or KSP
- Coexists with auto-generated routes
- Supports middleware and Guards

---

### Configuration

Neton uses a TOML-based configuration system.  
All modules (http/logging/database/redis/routing) are loaded through a unified ConfigLoader.

```toml
# application.conf

[application]
name = "Neton App"
debug = true

[server]
port = 8080
host = "0.0.0.0"

[logging]
level = "INFO"

[[logging.sinks]]
name = "all"
file = "logs/all.log"
levels = "ALL"
```

---

### Networking: Neton's own stack by default

Every Neton component networks through `neton-http`, which is built on Neton's own Kotlin/Native
io / http / tls libraries (coroutine reactors, no Rust or Ktor engine underneath):

| Role | Default | Entry point |
|------|---------|-------------|
| HTTP server (HTTP/1.1, h2, h2c, TLS + ALPN, SSE, gzip) | `DefaultHttpAdapter` | `http { port = 8080 }` |
| Outbound HTTP client (h1/h2 pooling, TLS, streaming, cancellation, HTTP proxy) | built-in client | `HttpClient.create { }` |
| SMTP (implicit TLS 465, STARTTLS 587) | built-in transport | `defaultSmtpTransport()` |
| WebSocket | `neton-websocket`'s default provider (netonstream websocket) | `websocket { }` |

The `neton` umbrella brings all of this; no engine module is needed. `neton-http-hyper4k` and
`neton-http-ktor` remain only as explicit, opt-in alternatives (`http(::Hyper4kHttpAdapter)`,
`HttpClient.createWith(::createKtorHttpClient)`); adding one never changes a default. Engine
selection is compile-time application code, not an `application.conf` setting or a runtime registry.
See [default engine](neton-http/DEFAULT_ENGINE.md).

### Optional WebSocket Engine Integration

`neton-websocket` includes its own default provider and allows an explicit third-party provider.
It is not included by the umbrella dependency. DSL and annotated routes, managed sessions,
heartbeat and bounded queues are implemented; see [usage and validation status](neton-websocket/README.md).

### Mail (SMTP transport)

`neton-http` defines `neton.http.mail.SmtpTransport` and ships its implementation on the same stack
as the HTTP client: implicit TLS on 465, STARTTLS on 587 (refused if the server does not offer it),
certificates checked against the OS trust store, AUTH PLAIN / LOGIN. Applications bind it like the
HTTP client:

```kotlin
bind(SmtpTransport::class, defaultSmtpTransport())
```

---

### Logging

Built-in structured logging:

- Multi-sink support
- Async writing (debug/info may drop, warn/error guaranteed)
- Auto-injected traceId/spanId

Output example (JSON):

```json
{
  "ts": "2026-02-13T10:21:33.123Z",
  "level": "INFO",
  "service": "neton-app",
  "traceId": "abc123",
  "msg": "http.request",
  "method": "GET",
  "path": "/",
  "status": 200,
  "latencyMs": 5
}
```

---

### Security (JWT)

Neton provides a built-in JWT authentication/authorization system:

```kotlin
@Get("/profile")
fun profile(@CurrentUser user: User): User {
    return user
}
```

- Built-in Guard/Authenticator mechanism
- Stable JWT primary path
- Composable security DSL

> SessionAuth / BasicAuth are experimental in v1

---

### Database (Entity + Table + Logic)

**Table (Single-table CRUD)**

KSP auto-generates single-table operations:

```kotlin
UserTable.get(id)
UserTable.query { where { ColumnRef("status") eq id } }.list()
UserTable.destroy(id)
```

**Logic (Business aggregation)**

```kotlin
class UserLogic(private val db: DbContext = dbContext()) : DbContext by db {
    suspend fun getWithRoles(id: Long): UserWithRoles? { ... }
}
```

Principles:

- Controller → Logic → Table → Model (frozen layering)
- Table = single-table CRUD (KSP generated)
- Logic = business aggregation (multi-table joins / transactions / domain)

---

### Route Groups & Mounting

Organize routes by group:

```kotlin
routing {
    group("admin") {
        get("/dashboard") { ... }
    }
}
```

The framework mounts paths under the group prefix and automatically applies the corresponding Guard/Authenticator.

---

### Contract Tests

Core behaviors are locked down via contract tests:

| Contract | Coverage |
|----------|----------|
| Config | Priority/override/ENV/CLI/fail-fast |
| Logging | Sinks/async/error guaranteed/field freeze |
| HTTP | Commit semantics/access log field freeze |
| Security/JWT | Error codes/auth/Guard behavior |
| Database | Entity/Table/Query DSL semantics |

---

## Examples

### HelloWorld

```bash
# macOS ARM64
./gradlew :examples:helloworld:linkDebugExecutableMacosArm64
cd examples/helloworld && ./build/bin/macosArm64/debugExecutable/helloworld.kexe

# Linux x64
./gradlew :examples:helloworld:linkDebugExecutableLinuxX64

# Linux ARM64
./gradlew :examples:helloworld:linkDebugExecutableLinuxArm64

# Windows x64
./gradlew :examples:helloworld:linkDebugExecutableMingwX64
```

### Cross-compiling Linux on macOS

Linux executables can be built on macOS when a Linux GCC cross toolchain is available. The default executable names are provided by Homebrew's `macos-cross-toolchains` packages:

```bash
brew tap messense/macos-cross-toolchains
brew install x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu
```

Custom toolchain paths can be supplied through `NETON_LINUX_X64_CC`, `NETON_LINUX_X64_AR`, `NETON_LINUX_ARM64_CC`, and `NETON_LINUX_ARM64_AR`, or the corresponding Gradle properties `neton.linuxX64.cc`, `neton.linuxX64.ar`, `neton.linuxArm64.cc`, and `neton.linuxArm64.ar`.

Cross-compilation verifies linking only. Run the produced executable and runtime smoke tests on the target Linux architecture before release.

Windows builds use the MinGW GCC toolchain distributed with Kotlin/Native for the small native lifecycle bridge. Custom tools can be selected with `NETON_MINGW_X64_CC` and `NETON_MINGW_X64_AR`, or the corresponding Gradle properties `neton.mingwX64.cc` and `neton.mingwX64.ar`.

**Visit:** http://localhost:8080/

**Response:**

```
Hello Neton!
```

---

## Performance

| Metric | Value |
|--------|-------|
| Startup time | 0.003s |
| Memory usage | ~12 MB |
| Binary size | ~3.5 MB |

- **Startup time**: Measured from process start to HTTP port listening
- Varies by build mode (Debug/Release), hardware, and I/O conditions

---

## Modules

| Module | Responsibility | Status |
|--------|---------------|--------|
| neton | Entry coordinate: core + logging + http + routing, plus BOM constraints | Stable |
| neton-bom | Version alignment for all modules | Stable |
| neton-core | Bootstrap / components / config | Stable |
| neton-http | Built-in HTTP server, outbound HTTP client and SMTP on Neton's io / http / tls | Stable |
| neton-logging | Structured logging + sinks/async | Stable |
| neton-routing | Routing DSL + KSP Controller | Stable |
| neton-security | Guard + JWT | Stable |
| neton-database | Entity + Table architecture, Query DSL | Stable |
| neton-redis | Redis + distributed lock | Stable |
| neton-cache | L1/L2 Cache | Stable |
| neton-storage | File storage (Local + S3) | Stable |
| neton-jobs | Job scheduling (Cron + FixedRate) | Stable |
| neton-ksp | Compile-time code generation | Stable |

---

## License

Apache 2.0 License

---

## Contributing

Issues and PRs are welcome.  
Check the `examples/` directory to get started.

---

## Acknowledgements

Neton is built on top of these excellent open-source projects:

| Project | Usage | Link |
|---------|-------|------|
| **Ktor** | Optional engine adapter (`neton-http-ktor`) | [github.com/ktorio/ktor](https://github.com/ktorio/ktor) |
| **hyper4k** | Optional Tokio + Hyper engine adapter (`neton-http-hyper4k`) | [github.com/netonframework/hyper4k](https://github.com/netonframework/hyper4k) |
| **kotlinx.coroutines** | Kotlin coroutines | [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| **kotlinx.serialization** | JSON / Protobuf serialization | [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| **sqlx4k** | Kotlin/Native database driver (SQLite / PostgreSQL / MySQL) | [github.com/smyrgeorge/sqlx4k](https://github.com/smyrgeorge/sqlx4k) |
| **re.this** | Kotlin Multiplatform Redis client | [github.com/vendelieu/re.this](https://github.com/vendelieu/re.this) |
| **cryptography-kotlin** | Kotlin Multiplatform cryptography (JWT / HMAC) | [github.com/whyoleg/cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) |
| **Konform** | Kotlin Multiplatform validation | [github.com/konform-kt/konform](https://github.com/konform-kt/konform) |
| **KSP** | Kotlin Symbol Processing for compile-time codegen | [github.com/google/ksp](https://github.com/google/ksp) |

Thanks to all the authors and contributors of these projects!
