# Neton HTTP 模块

HTTP 模块为 Neton 框架提供默认的 Ktor HTTP 服务器实现。默认构建和应用只加载 Ktor，
不会依赖或链接 hyper4k。

## 🎯 设计原则

按照依赖倒置原则设计：

- **Core 模块**：定义 `HttpAdapter` 接口标准
- **HTTP 模块**：依赖 Core 模块，实现具体的 HTTP 服务器功能
- **Mock 实现**：当 HTTP 模块不存在时，Core 模块使用 Mock 实现

## 🏗️ 架构设计

```
┌─────────────────┐    依赖    ┌─────────────────┐
│ neton-http      │ ---------> │   Core 模块     │
│ KtorHttpAdapter │            │ HttpAdapter接口 │
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
- 默认创建 `KtorHttpAdapter`
- 非默认引擎通过应用显式注册的 `HttpAdapterProvider` 创建

### HyperHttpAdapter

Hyper 支持位于可选模块 `neton-http-hyper4k`，不属于 `neton-http` 的默认依赖。
Kotlin/Native 在编译期完成链接，因此应用需要先引入并注册 Hyper Adapter，再通过配置选择：

```kotlin
import neton.http.http
import neton.http.hyper4k.enableHyper4kAdapter

Neton.run(args) {
    enableHyper4kAdapter()
    http { port = 8080 }
}
```

```toml
[http]
engine = "hyper4k"
```

如果不配置 `http.engine`，默认值始终是 `ktor`。如果配置了 `hyper4k` 却没有注册对应
Provider，应用会在启动时直接报告适配器未安装，而不会静默回退到 Ktor。

框架仓库中测试可选模块时使用：

```bash
./gradlew -Pneton.http.hyper4k=true :neton-http-hyper4k:macosArm64Test
```

该开发参数只负责包含本地适配器模块和相邻的 `../hyper4k` composite build，不影响应用的
运行时引擎选择。
- Supports JSON, URL-encoded forms, security, CORS, mounted routes, and response envelopes
- Multipart upload support remains on the Ktor adapter

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

只有选择 Hyper 的应用才添加：

```kotlin
dependencies {
    implementation("com.netonframework:neton-http-hyper4k:<neton-version>")
}
```

Adapter 模块会传递并锁定兼容的 `hyper4k` 引擎版本，应用不需要重复声明。

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

HTTP 后端采用“应用引入 Adapter + 配置选择 Engine”的方式切换：

1. 引入可选 Adapter 模块。
2. 在启动 DSL 中注册其 `HttpAdapterProvider`。
3. 在 `application.conf` 中设置 `http.engine`。

```kotlin
// 默认 Ktor，无需注册其他 Provider
Neton.run(args) {
    http { port = 8080 }
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
