package neton.ai.protocol.diag

import neton.ai.protocol.ir.WireFormat

enum class Severity {
    /** 展示层面的差异，或厂商官方明确为非必需的信息丢失。 */
    WARNING,
    /** 工具行为、内容或必要状态发生了变化。 */
    ERROR,
}

/** 一条转换诊断（spec §5.2）。 */
data class Diagnostic(
    val code: String,
    val path: String,
    val message: String,
    val severity: Severity,
    val source: WireFormat? = null,
    val target: WireFormat? = null,
)

/** 诊断收集器。编解码器只往里写，是否拒绝由 [LossPolicy] 决定。 */
class Diagnostics(val source: WireFormat? = null, val target: WireFormat? = null) {
    private val list = mutableListOf<Diagnostic>()
    val items: List<Diagnostic> get() = list

    fun warn(code: String, path: String, message: String) {
        list += Diagnostic(code, path, message, Severity.WARNING, source, target)
    }

    fun error(code: String, path: String, message: String) {
        list += Diagnostic(code, path, message, Severity.ERROR, source, target)
    }

    fun add(d: Diagnostic) { list += d }

    fun isEmpty(): Boolean = list.isEmpty()
}

/** 常用诊断代码。应用可以按代码配置 [LossPolicy.Safe.allowedLosses]。 */
object DiagnosticCodes {
    const val HOSTED_TOOL_OMITTED = "hosted_tool_omitted"
    const val CACHE_CONTROL_DROPPED = "cache_control_dropped"
    const val SIGNATURE_DROPPED = "signature_dropped"
    const val REASONING_DROPPED = "reasoning_dropped"
    const val N_GT_1_UNSUPPORTED = "n_gt_1_unsupported"
    const val UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    const val UNSUPPORTED_OPTION = "unsupported_option"
    const val INSTRUCTION_MOVED = "instruction_moved"
    const val CITATIONS_DROPPED = "citations_dropped"
    const val REFUSAL_AS_TEXT = "refusal_as_text"
    const val TOOL_RESULT_CONTENT_FLATTENED = "tool_result_content_flattened"
    const val CUSTOM_TOOL_AS_FUNCTION = "custom_tool_as_function"
    const val MALFORMED_INPUT = "malformed_input"
    const val UNSUPPORTED_CONTENT = "unsupported_content"
    const val THINKING_DISABLED = "thinking_disabled"
    const val GEMINI_THOUGHT_SIGNATURE_MISSING = "gemini_thought_signature_missing"
    const val GEMINI_THOUGHT_SIGNATURE_BYPASSED = "gemini_thought_signature_bypassed"
}

/** 损耗策略（spec §5.3）。只作用于请求阶段；响应与流式在三种策略下都完成转换并返回诊断。 */
sealed interface LossPolicy {
    /** 完成转换并返回诊断（默认）。 */
    data object Allow : LossPolicy

    /** 出现 error 级诊断时拒绝；[allowedLosses] 内的代码放行（已授权降级），诊断照常记录。 */
    data class Safe(val allowedLosses: Set<String> = emptySet()) : LossPolicy

    /** 出现任何诊断都拒绝，不受白名单影响。 */
    data object Strict : LossPolicy

    /** 按策略找出导致拒绝的诊断；空列表表示放行。 */
    fun violations(diagnostics: List<Diagnostic>): List<Diagnostic> = when (this) {
        Allow -> emptyList()
        is Safe -> diagnostics.filter { it.severity == Severity.ERROR && it.code !in allowedLosses }
        Strict -> diagnostics
    }
}

class ConversionRejectedException(val violations: List<Diagnostic>) :
    RuntimeException("conversion rejected by loss policy: " + violations.joinToString { "${it.code}@${it.path}" })
