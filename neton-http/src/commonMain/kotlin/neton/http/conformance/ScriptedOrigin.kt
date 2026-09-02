package neton.http.conformance

import neton.core.http.HttpHeaders

/**
 * 客户端一致性套件的对端（spec zh-hans/spec/http-engine.md §6.2）。
 *
 * 一个引擎无关的最小 HTTP/1.1 origin：监听 loopback，把每个连接上的请求解析成
 * [RecordedRequest]，然后把响应的写法交给脚本。它**不复用任何被测引擎的 Server
 * 侧**——否则 hyper4k client 的测试通过与否会和 hyper4k server 的正确性纠缠，
 * 一处回归两处红。
 *
 * 每个连接只服务一个请求，响应带 `Connection: close`。keep-alive 是引擎该做对
 * 的事，但不是这里要测的事；origin 越简单，测试红了越容易知道是谁的问题。
 *
 * 只在 POSIX 平台可用。mingw 上的 `actual` 直接抛出：在没有 origin 的平台上
 * 让套件红掉，好过让它静默全绿。
 */
expect class ScriptedOrigin {
    /** 监听地址，形如 `http://127.0.0.1:PORT`。 */
    val baseUrl: String

    /** 已经解析出来的请求，按到达顺序。 */
    val requests: List<RecordedRequest>

    /** 停止监听、断开所有连接、等待处理协程退出。幂等。 */
    suspend fun stop()

    companion object {
        /**
         * 启动一个 origin。[handle] 在每个连接上被调用一次，负责写出响应；
         * 它返回或抛出后连接关闭。
         */
        suspend fun start(handle: suspend OriginConnection.(RecordedRequest) -> Unit): ScriptedOrigin

        /** 一个此刻没有人监听的端口，用来测「连接被拒」。 */
        fun unusedPort(): Int
    }
}

/** origin 看到的一个请求。 */
class RecordedRequest(
    /** 请求行原文，代理测试要看它是不是绝对 URI。 */
    val requestLine: String,
    val method: String,
    val target: String,
    val headers: HttpHeaders,
    val body: ByteArray,
)

/**
 * 脚本对一个连接的写入面。
 *
 * 分两种写法，因为它们在线上的形状不同，而客户端**必须两种都处理对**：
 * - [writeFixed]：`Content-Length` 定长响应；
 * - [writeHead] + [writeChunk] + [end]：`Transfer-Encoding: chunked`，流式测试用。
 */
interface OriginConnection {
    /** 定长响应，一次写完。 */
    suspend fun writeFixed(status: Int, headers: HttpHeaders = HttpHeaders.EMPTY, body: ByteArray = ByteArray(0))

    /** 写状态行与头，声明 chunked。之后只能 [writeChunk] / [end] / [abort]。 */
    suspend fun writeHead(status: Int, headers: HttpHeaders = HttpHeaders.EMPTY)

    /** 写一个 chunk 并立即 flush。 */
    suspend fun writeChunk(bytes: ByteArray)

    /** 写终止 chunk。 */
    suspend fun end()

    /** 不写完直接断开。用来测截断。 */
    suspend fun abort()

    /**
     * 等对端关闭连接。取消传播测试靠它：客户端取消 Flow 后，origin 应该在
     * [timeoutMillis] 内看到 FIN。返回 false 表示没等到。
     */
    suspend fun awaitPeerClosed(timeoutMillis: Long): Boolean
}
