package neton.http.client

/**
 * 出站客户端的能力（spec zh-hans/spec/http-engine.md §5.2）。
 *
 * 与 Server 侧 `HttpCapability` 同一判据：**缺失时应用是「错」而不是「慢」的能力
 * 才进枚举。** 「支持 gzip」这类可协商、缺了只是慢一点的特性不登记——枚举一旦
 * 泛化成 feature flag 列表，启动期校验就变成噪音。
 *
 * 新增能力必须同时更新所有内置客户端的声明，并在 `HttpClientConformanceSuite`
 * 里补对应的条件测试；声明了能力却没有测试守着，等于没声明。
 */
enum class HttpClientCapability {
    /** 能协商并使用 HTTP/2。对只说 h2 的上游（gRPC 网关等）缺它即失败。 */
    HTTP_2,

    /**
     * 响应体真流式：chunk 到达即 emit，不等响应结束。消费 SSE 的前提。
     * 不声明它的客户端，`stream()` 只是把整个 body 攒完再一次吐出——SSE 在上面
     * 不报错但行为错误。
     */
    STREAMING_BODY,

    /**
     * Flow 取消能关闭底层连接或 stream。缺它时取消只是停止 collect，
     * 服务端继续生成、连接继续占用。
     */
    CANCELLATION,

    /** 支持自定义 CA（私有 PKI）。 */
    CUSTOM_CA,

    /** 支持 HTTP 代理。 */
    PROXY,
}
