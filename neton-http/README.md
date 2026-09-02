# Neton HTTP 模块

`neton-http` 是 HTTP 的**契约层**：入站 `HttpAdapter`、出站 `HttpClient`、共享的
`BufferedHttpDispatcher`（路由、安全、限流、CORS、响应信封）、错误模型，以及两套
引擎一致性套件。它**不引用任何引擎**。

引擎由**引擎模块**交付，且一个引擎模块同时交付 Server 与 Client 两个入口
（spec：`neton-docs/docs/zh-hans/spec/http-engine.md`）：

| 模块 | Server | Client | 说明 |
|---|---|---|---|
| `neton-http-hyper4k` | `http { }` | `HttpClient.create { }` | **默认**。`com.netonstream:neton` 聚合只装它 |
| `neton-http-ktor` | 同上 | 同上 | 维护模式；不进聚合、不进 BOM，只能显式依赖 |

两个入口都声明在契约层的包下（`neton.http` / `neton.http.client`），应用源码
里**不出现引擎名**；换引擎 = 改一行 build 文件。

## 使用

```kotlin
// build.gradle.kts —— 一行依赖，Server 与 Client 都有了
implementation("com.netonstream:neton:1.0.0-beta4")
```

```kotlin
import neton.http.http
import neton.http.client.HttpClient
import neton.http.client.create

Neton.run(args) {
    // 出站 Client 是应用的资源：应用创建、绑定、关闭。neton-ai / neton-storage 只借用。
    val client = HttpClient.create { requestMillis = 30_000 }
    bind(HttpClient::class, client)

    http { port = 8080 }
}
```

显式选引擎：`http(::KtorHttpAdapter) { }` / `HttpClient.createWith(factory) { }`。

### 没有引擎时

契约层带一个只在缺引擎时才会被选中的 fallback 重载，所以错误是可读的，而不是
`Unresolved reference`：

```
e: 'HttpClient.create(...)' is deprecated. No HTTP engine on the classpath.
   HttpClient.create { } is provided by an engine module: add
   com.netonstream:neton-http-hyper4k, or depend on com.netonstream:neton which includes it.
```

调用方需要 `import neton.http.client.create`（引擎的入口与 fallback 都是该包下的
扩展函数）。

## 出站 Client

```kotlin
interface HttpClient {
    suspend fun request(request: HttpClientRequest): HttpClientResponse   // 任意状态码都作为响应返回
    fun stream(request: HttpClientRequest): Flow<HttpClientStreamChunk>   // 非 2xx 在第一个 chunk 前抛 Http
    suspend fun close()
    val capabilities: Set<HttpClientCapability>
}
```

`capabilities` 无默认实现。`createWith` 会在返回前拒绝引擎不具备的配置——例如在没有
`PROXY` 能力的引擎上设置 `proxyUrl`——而不是静默忽略。

## 一致性套件与 testkit

- `neton.http.conformance.HttpEngineConformanceSuite`：Server 侧，每个引擎模块跑一遍。
- `neton.http.conformance.HttpClientConformanceSuite`：Client 侧，自带引擎无关的
  `ScriptedOrigin`（POSIX 目标）。声明了某能力却跳过对应测试 = 构建失败。
- `neton.http.testkit.ScriptedHttpClient`：消费方测试用的脚本化客户端，零引擎。

这些套件是「删掉任何一个引擎，另一个的通过状态不变」的证明，不是承诺。

## 配置

HTTP 只读 `application.conf` 的 `[server]` / `[http]` / `[cors]`；DSL 里的值只是
代码默认值（详见 `neton-docs` 的 http.md §6）。

## 边界

- 框架内出站请求只经 `HttpClient` 接口；`io.ktor` 只允许出现在 `neton-http-ktor/`，CI 用 grep 守着。
- Server 侧不终止 TLS，HTTP/2 的含义是 h2c；TLS 与 ALPN 由前置反向代理承担。
- 第三方引擎在独立仓库发布；主仓不引用、不枚举。
