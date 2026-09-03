package neton.http.client

/**
 * 应用交给模块的「按需建客户端」能力。
 *
 * 绝大多数模块只需要借用应用绑定的那一个 [HttpClient]。少数模块必须按运行时
 * 配置建多个——AI 网关按上游渠道各自的代理与超时建客户端——它们不能自己调
 * `HttpClient.create { }`：那要求模块带引擎，而带引擎的模块一多，应用类路径上就有
 * 两个 `create`。所以由应用把「怎么建」封成这个接口绑定进 `NetonContext`：
 *
 * ```kotlin
 * bind(HttpClientProvider::class, HttpClientProvider { HttpClient.create(it) })
 * ```
 *
 * 引擎仍然只在应用的 build 文件里出现一次。模块拿到的客户端由模块自己负责关闭。
 */
fun interface HttpClientProvider {
    fun create(block: HttpClientConfig.() -> Unit): HttpClient
}
