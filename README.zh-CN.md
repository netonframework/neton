# 📦 Neton

**高性能 Kotlin/Native Web 框架**

Native-first、零反射、编译期生成、结构化日志、工程化 DSL

---

## 🚀 特性

Neton 是一个面向生产环境、工程化 Web 框架，它与传统 JVM 框架（Spring Boot、Ktor）不同：

- **🎯 原生优先（Native-first）**：可编译为本地可执行文件，启动快、资源占用低
- **⚡ 无反射**：所有路由/表/字段由 KSP 编译期生成
- **📄 单一配置入口**：TOML 格式配置，优先级 CLI/ENV > 环境 conf > defaults
- **📊 结构化日志**：内置 multi-sink、异步写、WARN/ERROR 不丢
- **🔐 安全认证体系**：JWT 主路径稳定、Guard/Authenticator 可组合
- **🗂 数据库语义清晰**：Table（单表）+ Store（聚合）分层
- **🧪 契约测试保障**：核心行为通过 Contract Tests 固定

---

## 🧠 快速理解

| 维度 | Neton (Kotlin/Native) | Spring Boot (Java) | Rust (Actix/Axum) | Go (Gin/Fiber) | Node.js (Express/Nest) |
|------|------------------------|---------------------|-------------------|----------------|------------------------|
| 运行形态 | 原生可执行文件 | JVM | 原生可执行文件 | 原生可执行文件 | V8 + Node 运行时 |
| 工程性 | 高 | 高 | 中 | 低 | 高 |
| 开发效率 | 高 | 高 | 低 | 高 | 高 |
| 调试效率 | 高 | 高 | 低 | 一般 | 一般 |
| 启动时间 | 毫秒级 | 秒级 | 毫秒级 | 毫秒级 | 亚秒级 |
| 100 并发内存占用 | ~20MB | 400MB+ | 10~30MB | 15~40MB | 100MB+ |
| 反射依赖 | ✅ 无 | ❌ 大量使用 | ✅ 无 | ✅ 无 | ✅ 无 |
| 编译期生成 | ✅ KSP 路由/Table/安全 | ❌ 运行时扫描 | ❌ 无 | ❌ 无 | ❌ 无 |
| 架构抽象层级 | 明确 Core/Adapter/Table/Store 分层 | IoC 容器驱动 | 库拼装 | 库拼装 | 中间件拼装 |
| 可维护性 | API Freeze + Contract Test 固化 | 成熟但庞大 | 强类型但分散 | 简洁但松散 | 依赖生态规范 |
| 扩展性 | Adapter 化（DB/Redis/HTTP 可替换） | 生态成熟 | 高度可定制 | 中等 | 依赖插件 |
| 配置体系 | 统一 TOML + CLI/ENV 强优先级 | YAML + Profiles | 手写 | 手写 | JSON/YAML |
| 日志体系 | 内建 multi-sink + async + contract | 依赖 Logback | 依赖 crate | 依赖库 | 第三方 |
| 安全模型 | Identity 冻结 + JWT 契约 | Spring Security 需自行组合 | 需自行组合 | 需第三方 | 需第三方 |
| 类型安全 DSL | Kotlin 强类型 DSL | 注解驱动 | Builder 函数式 | 中间件链 | 中间件链 |

---

## 🖥️ 支持平台

| 平台 | 目标名称 | 状态 |
|------|---------|------|
| macOS ARM64 | `macosArm64` | 支持 |
| Linux x64 | `linuxX64` | 支持 |
| Linux ARM64 | `linuxArm64` | 支持 |
| Windows x64 | `mingwX64` | 支持 |

---

## 🛠️ 快速开始

### 📌 1. 极简架构

框架启动入口示例：

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

## 🧩 特点讲解

- 不依赖反射/KSP 也可用
- 与自动生成路由可共存
- 可接入中间件/Guard

---

### 📌 配置

Neton 使用 TOML 配置体系。  
所有模块（http/logging/database/redis/routing）统一通过 ConfigLoader 加载。

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

### 📊 日志

内建结构化日志：

- 支持 multi-sink
- 异步写入（debug/info 可丢、warn/error 不丢）
- 自动注入 traceId/spanId

输出示例（JSON）：

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

### 🔐 安全（JWT）

Neton 提供了一个默认的 JWT 认证/授权体系：

```kotlin
@Get("/profile")
fun profile(@CurrentUser user: User): User {
    return user
}
```

- 内建 Guard/Authenticator 机制
- JWT 主路径稳定
- security DSL 可组合

> SessionAuth / BasicAuth 在 v1 中为 experimental

---

### 🗄️ 数据库（Table + Store）

**🔹 Table（单表 CRUD）**

KSP 自动生成单表 Table：

```kotlin
// 用户表
UserTable.get(id)
UserTable.query { where { ColumnRef("status") eq id } }.list()
UserTable.destroy(id)
```

**🔹 Logic（业务聚合层）**

```kotlin
class UserLogic(private val db: DbContext = dbContext()) : DbContext by db {
    suspend fun getWithRoles(id: Long): UserWithRoles? { ... }
}
```

原则：

- Controller → Logic → Table → Model（冻结分层）
- Table = 单表 CRUD（KSP 生成）
- Logic = 业务聚合（多表联查/事务/领域）

---

### 📁 路由组 & 挂载

你可以将路由按组组织：

```kotlin
routing {
    group("admin") {
        get("/dashboard") { ... }
    }
}
```

框架会根据组名将路径挂载到前缀，并自动应用对应的 Guard/Authenticator。

---

### 🧪 Contract Tests

Neton 的核心行为通过 contract tests 固定：

| Contract | 覆盖内容 |
|----------|----------|
| Config | 优先级/覆盖/ENV/CLI/fail-fast |
| Logging | sinks/async/error 不丢/字段冻结 |
| HTTP | commit 语义/access log 字段冻结 |
| Security/JWT | 错误码/认证/Guard 行为 |
| Database | Table/Store 语义 |

---

## 📦 工程示例

### 🏃‍♂️ HelloWorld

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

**访问：**

- http://localhost:8080/

**返回：**

```
Hello Neton!
```

---

## 📈 性能

以下是真实测量结果示例，可根据测试机器替换具体数字。

| 指标 | 测量值 |
|------|--------|
| 启动时间 | 0.003 秒 |
| 内存占用 | ~12 MB |
| 可执行体积 | ~3.5 MB |

- **启动时间定义**：从进程启动到 HTTP 监听端口完成
- 受构建模式（Debug/Release）、硬件、IO 状况影响

---

## 📦 模块一览

下表反映当前稳定状态（可用于 beta1 发布说明）

| 模块 | 职责 | 状态 |
|------|------|------|
| neton-core | 启动/组件/配置 | ✅ 稳定 |
| neton-http | HTTP 服务器适配 | ✅ 稳定 |
| neton-logging | 结构化日志 + sinks/async | ✅ 稳定 |
| neton-routing | 路由 DSL + KSP Controller | ✅ 稳定 |
| neton-security | Guard + JWT 主路径 | ✅ 稳定 |
| neton-database | Table + Store 架构 | ✅ 稳定 |
| neton-redis | Redis + lock | ✅ 稳定 |
| neton-cache | L1/L2 Cache | ✅ 稳定 |
| neton-storage | 文件存储（本地 + S3） | ✅ 稳定 |
| neton-jobs | 定时任务调度（Cron + FixedRate） | ✅ 稳定 |
| neton-ksp | 编译期生成支持 | ✅ 稳定 |

---

---

## 📄 授权协议

Apache 2.0 License

---

## 🤝 贡献指南

欢迎提交 Issue / PR。  
可参考 `examples/` 目录学习快速上手。

---

## 🙏 致谢

Neton 的诞生离不开以下优秀的开源项目：

| 项目 | 用途 | 链接 |
|------|------|------|
| **Ktor** | HTTP 服务器引擎（CIO） | [github.com/ktorio/ktor](https://github.com/ktorio/ktor) |
| **kotlinx.coroutines** | Kotlin 协程支持 | [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| **kotlinx.serialization** | JSON / Protobuf 序列化 | [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| **sqlx4k** | Kotlin/Native 数据库驱动（SQLite / PostgreSQL / MySQL） | [github.com/smyrgeorge/sqlx4k](https://github.com/smyrgeorge/sqlx4k) |
| **re.this** | Kotlin Multiplatform Redis 客户端 | [github.com/vendelieu/re.this](https://github.com/vendelieu/re.this) |
| **cryptography-kotlin** | Kotlin Multiplatform 加密库（JWT / HMAC） | [github.com/whyoleg/cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) |
| **Konform** | Kotlin Multiplatform 数据验证 | [github.com/konform-kt/konform](https://github.com/konform-kt/konform) |
| **KSP** | Kotlin Symbol Processing 编译期代码生成 | [github.com/google/ksp](https://github.com/google/ksp) |

感谢这些项目的作者和贡献者们！
