package neton.core.http.adapter

/**
 * HTTP 引擎能力（spec zh-hans/spec/http-engine-capabilities.md §2.1）。
 *
 * 抽象层描述的是**形状**（HttpAdapter / HttpContext / 生命周期），
 * 本枚举描述的是**能力**。两者分开的理由是一类真实故障：
 * [neton.core.http.HttpResponse.stream] 的默认实现会把多次 `writeChunk`
 * **静默缓冲成一次 write**，于是 SSE 在不支持真流式的引擎上**不报错、但行为错误**
 * ——事件全堆到响应结束才一次吐出。这种问题长得像网络慢、像上游卡，
 * 唯独不像"引擎不支持"，排查成本极高。
 *
 * 能力声明 + 启动期校验就是为了把这类问题从运行时挪到启动期。
 *
 * **准入判据**：缺失该能力时，应用是**错**，还是只是**慢**？错才进枚举。
 * gzip、keep-alive 调参这类可协商、缺失只是变慢的特性**不**进——
 * 枚举一旦泛化成 feature flag 列表，启动期校验就变成噪音，没人再认真看。
 */
enum class HttpCapability {
    /** HTTP/2（h2c 或 h2）。声明它意味着引擎能协商并服务 HTTP/2 连接。 */
    HTTP_2,

    /**
     * **真**流式响应：`writeChunk` 立即下发，不等响应结束。
     * SSE / chunked relay 的前提；不声明它的引擎只有缓冲兼容路径。
     */
    STREAMING_RESPONSE,

    /** `multipart/form-data` 解析（文件上传）。 */
    MULTIPART,

    /**
     * 异步 handoff：handler 可以交还 I/O 线程后再完成响应。
     * 不具备时，长耗时 handler 会占住引擎 worker。
     */
    ASYNC_HANDOFF,

    /** 请求 / 响应 trailers。gRPC-over-HTTP/2 之类的前提。 */
    TRAILERS,
}

/**
 * 应用与组件声明「我需要哪些引擎能力」。
 *
 * 绑定进 [neton.core.component.NetonContext]，组件在 `prepare` / 配置阶段登记；
 * 框架在 `adapter.start()` **之前**校验（见 [validateHttpCapabilities]）。
 *
 * 必须记录 [requiredBy]：只说"缺 STREAMING_RESPONSE"的报错，使用者无从下手——
 * 得知道是谁要的，才知道该换引擎还是该去掉那个组件。
 */
class HttpCapabilityRequirements {
    private val byCapability = mutableMapOf<HttpCapability, MutableSet<String>>()

    fun require(capability: HttpCapability, requiredBy: String) {
        byCapability.getOrPut(capability) { linkedSetOf() }.add(requiredBy)
    }

    fun require(capabilities: Collection<HttpCapability>, requiredBy: String) {
        for (c in capabilities) require(c, requiredBy)
    }

    fun all(): Set<HttpCapability> = byCapability.keys.toSet()

    fun requesters(capability: HttpCapability): Set<String> =
        byCapability[capability]?.toSet() ?: emptySet()

    fun isEmpty(): Boolean = byCapability.isEmpty()
}

/** 启动期能力校验失败。**不降级运行**——降级正是能力模型要消灭的东西。 */
class HttpCapabilityException(message: String) : IllegalStateException(message)

/**
 * 校验引擎能力是否覆盖所有声明的需求。
 *
 * MUST 在 `adapter.start()` 之前调用：端口被占用之后才失败，会让一次配置错误
 * 看起来像端口冲突。
 */
fun validateHttpCapabilities(
    adapterName: String,
    provided: Set<HttpCapability>,
    requirements: HttpCapabilityRequirements,
) {
    val missing = requirements.all() - provided
    if (missing.isEmpty()) return

    val detail = missing.sortedBy { it.name }.joinToString("\n") { cap ->
        val who = requirements.requesters(cap)
        "    $cap 由以下要求：" + if (who.isEmpty()) "(未记录来源)" else who.joinToString("、")
    }
    throw HttpCapabilityException(
        buildString {
            appendLine("Neton 启动失败：HTTP 引擎能力不足")
            appendLine()
            appendLine("  引擎：$adapterName")
            appendLine("  缺失：" + missing.sortedBy { it.name }.joinToString("、"))
            appendLine()
            appendLine(detail)
            appendLine()
            appendLine("  可选处置：")
            appendLine("    · 换用具备该能力的引擎，如 Neton.LaunchBuilder().http(::Hyper4kHttpAdapter)")
            append("    · 或移除依赖该能力的组件")
        },
    )
}
