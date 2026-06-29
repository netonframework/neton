# Neton HTTP 模块

HTTP 模块为 Neton 框架提供真正的 HTTP 服务器实现，基于 Ktor 服务器。

## 🎯 设计原则

按照依赖倒置原则设计：

- **Core 模块**：定义 `HttpAdapter` 接口标准
- **HTTP 模块**：依赖 Core 模块，实现具体的 HTTP 服务器功能
- **Mock 实现**：当 HTTP 模块不存在时，Core 模块使用 Mock 实现

## 🏗️ 架构设计

```
┌─────────────────┐    依赖    ┌─────────────────┐
│   HTTP 模块     │ ---------> │   Core 模块     │
│ KtorHttpAdapter │            │ HttpAdapter接口 │
│                 │            │ MockHttpAdapter │
└─────────────────┘            └─────────────────┘
        │                             │
        │ init 时 ctx.bind            │ 提供接口
        ▼                             ▼
┌─────────────────────────────────────────────────┐
│            NetonContext（唯一容器）              │
│  ctx.get<HttpAdapter>() -> 实际实现 或 Mock      │
└─────────────────────────────────────────────────┘
```

## 🔧 主要组件

### KtorHttpAdapter
- 实现真正的 Ktor HTTP 服务器
- 支持所有 HTTP 方法（GET, POST, PUT, DELETE 等）
- 自动将 Ktor 请求转换为 Neton HttpContext
- 支持会话管理
- **JsonContent 响应**：检测 `JsonContent` 返回值，直接以 `application/json` 响应，绕过 Ktor content negotiation

### HttpComponent
- 负责模块初始化和注册
- 根据 `HttpEngine` 选择 adapter 并注册到 NetonContext

### HyperHttpAdapter

`HyperHttpAdapter` uses the standalone `hyper4k` Tokio + Hyper engine on Kotlin/Native.
Select it from the DSL or `application.conf`:

```kotlin
http {
    engine = HttpEngine.HYPER4K
}
```

```toml
[http]
engine = "hyper4k"
```

The default remains `ktor`. A local sibling `../hyper4k` repository is included through the
composite build during framework development.
- Supports JSON, URL-encoded forms, security, CORS, mounted routes, and response envelopes
- Multipart upload support remains on the Ktor adapter
- 通过 `http { }` install DSL 暴露，组件对业务层隐藏

### SecurityPreHandle
- 安全管道前置处理（认证 + 授权 + 权限检查）
- **permission implies auth** 规则：`@Permission` 注解隐含强制认证，即使路由组 requireAuth=false

### 泛型序列化（KSP 编译期生成）
- Kotlin/Native 下 Ktor 的 `guessSerializer()` 无法处理泛型 `@Serializable` 类型
- KSP `ControllerProcessor` 在编译期检测 `@Serializable` 返回类型，生成显式序列化代码
- 返回值包装为 `JsonContent(Json.encodeToString(serializer, result))`
- 支持嵌套泛型，如 `PageResponse<UserVO>`、`ApiResponse<PageResponse<UserVO>>`

## 📦 使用方式

### 1. 添加依赖

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":neton-core"))
    implementation(project(":neton-http"))
}
```

### 2. 使用 install DSL（推荐）

```kotlin
// Main.kt
import neton.core.Neton
import neton.http.http
import neton.security.security
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {

        http {
            port = 8080
        }

        security {
            registerMockAuthenticator("test-user", listOf("user", "admin"))
        }

        routing {
            // KSP 自动生成路由
        }

        onStart {
            println("Server at http://localhost:${getPort()}")
        }
    }
}
```

## 🚀 特性

- ✅ **真正的 Ktor 服务器**：完全替代 Mock 实现
- ✅ **浏览器访问支持**：可通过浏览器直接访问
- ✅ **自动适配**：无缝集成 Neton HttpContext
- ✅ **会话支持**：内置 Session 管理
- ✅ **错误处理**：完整的异常处理机制
- ✅ **可扩展性**：未来可支持其他 HTTP 库

## 🔄 切换 HTTP 后端

由于采用了依赖倒置设计，未来可以轻松切换到其他 HTTP 库：

1. 创建新的 HTTP 模块（如 `neton-http-netty`）
2. 实现 `HttpAdapter` 接口
3. 在应用中替换模块依赖

```kotlin
// 从 Ktor 切换到 Netty（示例）
Neton.run(args) {
    http { port = 8080 }  // 或 netty { } 若实现 NettyPlugin
    routing { }
}
```

## 🧪 开发状态

- ✅ 基础架构完成
- ✅ Ktor 服务器集成完成
- ✅ 完整的请求/响应处理
- ✅ 安全管道（认证 + 授权 + 权限）
- ✅ 泛型序列化（KSP 编译期 JsonContent）
- ✅ 契约测试：SecurityPipelineContractTest（15 条）、GenericSerializerContractTest（5 条）
- ⏳ 性能优化

## 📋 TODO

- [ ] 添加性能监控
- [ ] 支持 HTTPS
- [ ] 添加更多 HTTP 库支持（Netty、Vertx 等）
