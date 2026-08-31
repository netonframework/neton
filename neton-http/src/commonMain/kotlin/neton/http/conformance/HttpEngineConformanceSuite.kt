package neton.http.conformance

import neton.core.http.adapter.HttpAdapter
import neton.core.http.adapter.HttpCapability
import neton.http.adapter.BufferedHttpRequest
import neton.http.adapter.BufferedHttpResponse

/**
 * 引擎一致性套件（spec zh-hans/spec/http-engine-capabilities.md §5）。
 *
 * 抽象层的价值 = 「多个引擎行为一致」的**可验证**程度。在这套东西存在之前，
 * 可插拔只是声称：Ktor CIO 有 `KtorLiveResponse` 这条真流式路径而 hyper4k 没有，
 * 这个差异此前只能靠读代码发现——正是套件要钉死的那类事。
 *
 * ## 测什么
 *
 * **不测** [neton.http.adapter.BufferedHttpDispatcher] 本身——那是三个引擎共用的
 * 一份代码，测它只会把同一段逻辑测三遍。真正会漂移的是每个 Adapter 的**翻译层**：
 * 引擎原生请求 → [BufferedHttpRequest]，以及 [BufferedHttpResponse] → 引擎响应。
 * header 大小写、多值 header、query 切分、空 body 与缺失 body、非 UTF-8 字节，
 * 每一处都是各写各的。
 *
 * ## 怎么接
 *
 * 每个 Adapter 仓实现 [roundTrip]：把 [ConformanceRequest] 转成自己的引擎类型，
 * 喂给自己的 dispatch，再把结果转回 [ConformanceResponse]。不需要真的开端口——
 * 端口测的是 socket，这里测的是翻译。
 */
public abstract class HttpEngineConformanceSuite {

    /** 被测适配器。实现方通常直接 `Hyper4kHttpAdapter(HttpServerConfig(port = 0), ...)`。 */
    public abstract fun createAdapter(): HttpAdapter

    /**
     * 把请求经由**引擎自己的翻译层**走一遍。
     *
     * 实现必须真的经过 `引擎请求类型 → BufferedHttpRequest` 这一跳；
     * 直接构造 `BufferedHttpRequest` 交给共享 dispatcher 会让本套件失去意义。
     */
    public abstract suspend fun roundTrip(request: ConformanceRequest): ConformanceResponse

    /**
     * 记录一次「因引擎不具备该能力而跳过」。
     *
     * **必须显式记录，不得静默通过**（spec §5.2）：一个能力全被跳过、报告却全绿的
     * 套件等于没有套件。实现方把它接到测试框架的 skip/ignore 机制或至少打印出来。
     */
    public abstract fun recordSkipped(capability: HttpCapability, testName: String)

    /**
     * 能力守卫：声明了就必须真跑，没声明就记为 skipped。
     *
     * 反过来的那一半同样重要——**声明了某能力却让对应测试跳过 = 构建失败**
     * （spec §5.2）。所以这里在声明时**不**允许跳过：走的是 [block]，
     * 它失败就是失败，这是防「声明先行、实现拖延」的唯一闸门。
     */
    protected suspend fun requiring(
        capability: HttpCapability,
        testName: String,
        block: suspend () -> Unit,
    ) {
        if (capability in createAdapter().capabilities) block() else recordSkipped(capability, testName)
    }
}

/**
 * 引擎无关的请求描述。
 *
 * `headers` 用 `Map<String, List<String>>` 而不是 `Map<String, String>`：
 * 多值 header（`Set-Cookie`、`Accept`）正是各引擎最容易各写各的地方，
 * 单值模型会把这个差异从测试里抹掉。
 */
public class ConformanceRequest(
    public val method: String,
    public val path: String,
    public val query: String = "",
    public val headers: Map<String, List<String>> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
)

public class ConformanceResponse(
    public val status: Int,
    public val headers: Map<String, List<String>> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
)

/** 套件内部用的转换，供实现方复用，省得每个仓再写一遍。 */
public fun ConformanceRequest.toBuffered(): BufferedHttpRequest = BufferedHttpRequest(
    method = method,
    path = path,
    query = query,
    headers = headers,
    body = body,
)

public fun BufferedHttpResponse.toConformance(): ConformanceResponse = ConformanceResponse(
    status = status,
    headers = headers,
    body = body,
)
