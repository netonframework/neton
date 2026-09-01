# Neton HTTP 模块

HTTP 模块统一提供入站 Server 与出站 Client。Server 默认使用 Ktor，不会依赖或链接
Rust 引擎；Client 位于 `neton.http.client` 包，不再使用独立的 `neton-http-client` 模块。

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
- 接收任意满足 `HttpAdapterFactory` 签名的 Adapter 构造器

### NetonHttpClient

出站 Client 的完整能力已经合并到本模块：

- `NetonHttpClient.request()`：缓冲式请求/响应
- `NetonHttpClient.stream()`：支持取消传播的流式响应
- `NetonHttpError` / `NetonHttpException`：统一错误模型
- SSE parser 与 Flow 操作
- Header 脱敏和 retry primitive
- macOS Darwin、Linux CIO、Windows WinHttp 平台 Engine

可独立创建：

```kotlin
val client = NetonHttpClient.create {
    requestMillis = 30_000
}
```

也可以安装进 NetonContext，供 `neton-ai` 等组件使用：

```kotlin
Neton.run(args) {
    httpClient { requestMillis = 30_000 }
    ai { /* ... */ }
    http { port = 8080 }
}
```

入站 Server Adapter 的选择不改变出站 Client Engine。

### 外部 Server Adapter

Neton 主仓不引入第三方 Server Adapter。Kotlin/Native 应用添加对应依赖后，直接传入
Adapter 的构造器引用：

```kotlin
import neton.http.http
import neton.http.hyper4k.Hyper4kHttpAdapter

Neton.run(args) {
    http(::Hyper4kHttpAdapter) {
        port = 8080
    }
}
```

`http { }` 是 `http(::KtorHttpAdapter) { }` 的默认语法糖。第三方 Adapter 使用统一构造函数：

```kotlin
class XxxHttpAdapter(
    serverConfig: HttpServerConfig,
    converterRegistry: ParamConverterRegistry,
) : HttpAdapter
```

这里使用 constructor reference 而不是 `KClass`，因为 Kotlin/Native 不依赖运行时反射。

缓冲型外部引擎应复用 `neton.http.adapter.BufferedHttpDispatcher`。它统一实现路由、安全、
限流、CORS、响应信封和 `HttpContext`，Adapter 只负责把引擎请求映射成
`BufferedHttpRequest`，再将 `BufferedHttpResponse` 写回传输层。Hyper4k 和 Ktor
都使用这条标准管线。

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
    implementation("com.netonframework:neton-http-hyper4k:<adapter-version>")
}
```

Adapter 模块会传递并锁定兼容的 `hyper4k` 引擎版本，应用不需要重复声明。
Hyper Adapter 固定使用异步 handoff：Tokio 只负责连接与协议，请求快照交给有界的
Kotlin/Native 协程执行。它直接复用 `http.maxConnections` 与 `http.timeout`，不增加引擎线程池开关。

需要纯 Kotlin 实现（开发服务器、无 Rust 工具链的环境）时显式切到 Ktor：

```kotlin
dependencies {
    implementation("com.netonframework:neton-http-ktor:<adapter-version>")
}
```

Ktor 只能显式选用（`http(::KtorHttpAdapter)`），不提供无参 `http { }` 默认。

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

HTTP 后端由应用在编译期显式注入：

1. 引入可选 Adapter 模块。
2. 将 Adapter 构造器传给 `http(...)`。
3. `application.conf` 只配置端口、超时、CORS 等运行参数。

```kotlin
// 默认 Ktor
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

## 🗺️ Roadmap（1.0 之后）

默认引擎是 Hyper4k（`neton-http-hyper4k` 模块，无参 `http { }` 即解析到它）；
Ktor 作为纯 Kotlin 兼容实现保留，只能显式选用。

- **HTTPS/TLS 终止**：1.0 推荐由反向代理（Nginx / Caddy / 云负载均衡）承担 TLS；进程内 TLS 暂不提供。
- **性能监控 / metrics**：计划作为独立的 `neton-observability` 能力提供，不内嵌进 HTTP 模块。
- **更多 HTTP 引擎**：必须复用标准 Dispatcher 或通过完整一致性套件，不接受复制业务管线的 Adapter。
