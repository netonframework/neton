package neton.http.conformance

/**
 * Windows 上没有 origin 实现。
 *
 * 这里刻意抛出而不是返回一个空实现：一个在某平台上静默跳过所有网络断言的
 * 套件，报告全绿却什么都没测——spec §6.3 禁止这种情况。要在 Windows 上跑
 * 客户端一致性套件，需要用 winsock 补一份 `actual`。
 */
actual class ScriptedOrigin private constructor() {
    actual val baseUrl: String get() = unsupported()
    actual val requests: List<RecordedRequest> get() = unsupported()
    actual suspend fun stop(): Unit = unsupported()

    actual companion object {
        actual suspend fun start(handle: suspend OriginConnection.(RecordedRequest) -> Unit): ScriptedOrigin =
            unsupported()

        actual fun unusedPort(): Int = unsupported()
    }
}

private fun unsupported(): Nothing = throw UnsupportedOperationException(
    "ScriptedOrigin is not implemented on mingwX64; the client conformance suite " +
        "cannot run on this target until a winsock actual exists.",
)
