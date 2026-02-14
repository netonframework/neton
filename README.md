# Neton

**High-performance Kotlin/Native Web Framework**

Native-first, zero reflection, compile-time code generation, structured logging, engineering-grade DSL

[中文文档](README.zh-CN.md)

---

## Features

Neton is a production-ready, engineering-focused web framework that differs from traditional JVM frameworks (Spring Boot, Ktor):

- **Native-first**: Compiles to native executables with fast startup and low resource usage
- **Zero reflection**: All routes/tables/fields are generated at compile time via KSP
- **Unified configuration**: TOML-based config with priority: CLI/ENV > environment conf > defaults
- **Structured logging**: Built-in multi-sink, async writing, WARN/ERROR guaranteed delivery
- **Security**: JWT authentication with composable Guard/Authenticator
- **Clear database semantics**: Table (single-table CRUD) + Store (aggregate) layering
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
| Architecture | Core/Adapter/Table/Store layers | IoC container driven | Library composition | Library composition | Middleware composition |
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
| macOS x64 | `macosX64` | Supported |
| Linux x64 | `linuxX64` | Supported |
| Linux ARM64 | `linuxArm64` | Supported |
| Windows x64 | `mingwX64` | Supported |

---

## Quick Start

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

### Database (Table + Store)

**Table (Single-table CRUD)**

KSP auto-generates single-table operations:

```kotlin
UserTable.get(id)
UserTable.where { User::status eq id }.list()
UserTable.destroy(id)
```

**Store (Aggregate semantics)**

```kotlin
class UserStore {
    suspend fun getWithRoles(id: Long): UserWithRoles? { ... }
}
```

Principles:

- Table = single-table CRUD
- Store = aggregate logic (multi-table joins / domain)
- Never mix Table/Store semantics

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
| Database | Table/Store semantics |

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
| neton-core | Bootstrap / components / config | Stable |
| neton-http | HTTP server adapter | Stable |
| neton-logging | Structured logging + sinks/async | Stable |
| neton-routing | Routing DSL + KSP Controller | Stable |
| neton-security | Guard + JWT | Stable |
| neton-database | Table + Store architecture | Stable |
| neton-redis | Redis + distributed lock | Stable |
| neton-cache | L1/L2 Cache | Stable |
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
| **Ktor** | HTTP server engine (CIO) | [github.com/ktorio/ktor](https://github.com/ktorio/ktor) |
| **kotlinx.coroutines** | Kotlin coroutines | [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| **kotlinx.serialization** | JSON / Protobuf serialization | [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| **sqlx4k** | Kotlin/Native database driver (SQLite / PostgreSQL / MySQL) | [github.com/smyrgeorge/sqlx4k](https://github.com/smyrgeorge/sqlx4k) |
| **re.this** | Kotlin Multiplatform Redis client | [github.com/vendelieu/re.this](https://github.com/vendelieu/re.this) |
| **cryptography-kotlin** | Kotlin Multiplatform cryptography (JWT / HMAC) | [github.com/whyoleg/cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) |
| **Konform** | Kotlin Multiplatform validation | [github.com/konform-kt/konform](https://github.com/konform-kt/konform) |
| **KSP** | Kotlin Symbol Processing for compile-time codegen | [github.com/google/ksp](https://github.com/google/ksp) |

Thanks to all the authors and contributors of these projects!
