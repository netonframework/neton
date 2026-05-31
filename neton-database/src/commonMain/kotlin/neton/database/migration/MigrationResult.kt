package neton.database.migration

/**
 * Engine 执行结果。Pure 数据 — engine 不做 println / log,留给 CLI / application
 * 层格式化输出 + 决定退出码。
 */
sealed class MigrationResult {
    /** Engine 行为 — 全部成功(或无事可做)。 */
    abstract val warnings: List<String>

    /** 单脚本视图(status / verify 用)。 */
    data class ScriptView(
        val moduleId: String,
        val version: String,
        val description: String,
        val state: ScriptState,
        /** state == CHECKSUM_MISMATCH 时填,其它 null。 */
        val diskChecksum: String? = null,
        val historyChecksum: String? = null,
        /** state == FAILED 时填。 */
        val errorMessage: String? = null,
    )

    enum class ScriptState {
        EXECUTED,             // 已应用,checksum 匹配
        PENDING,              // 磁盘有,history 无
        CHECKSUM_MISMATCH,    // history 有但磁盘 checksum 变了
        MISSING_ON_DISK,      // history 有但磁盘文件没了
        FAILED,               // history 中标记 success=false
    }

    /** status 结果。 */
    data class Status(
        val historyTable: String,
        val historyExists: Boolean,
        val scripts: List<ScriptView>,
        override val warnings: List<String>,
    ) : MigrationResult() {
        val pendingCount: Int get() = scripts.count { it.state == ScriptState.PENDING }
        val mismatchCount: Int get() = scripts.count { it.state == ScriptState.CHECKSUM_MISMATCH }
        val failedCount: Int get() = scripts.count { it.state == ScriptState.FAILED }
        val executedCount: Int get() = scripts.count { it.state == ScriptState.EXECUTED }
    }

    /** verify 结果。 */
    data class Verify(
        val historyTable: String,
        val verifiedCount: Int,
        val mismatches: List<ScriptView>,
        val missing: List<ScriptView>,
        override val warnings: List<String>,
    ) : MigrationResult() {
        val ok: Boolean get() = mismatches.isEmpty() && missing.isEmpty()
    }

    /** up 结果。 */
    data class Up(
        val historyTable: String,
        val applied: List<AppliedScript>,
        val skipped: Int,
        val failedAt: AppliedScript? = null,
        override val warnings: List<String>,
    ) : MigrationResult() {
        val ok: Boolean get() = failedAt == null
    }

    data class AppliedScript(
        val moduleId: String,
        val version: String,
        val description: String,
        val executionMs: Long,
        val success: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Engine 启动期 fail-fast (scan / config 错误,根本到不了执行步骤)。
     */
    data class Aborted(
        val reason: String,
        override val warnings: List<String> = emptyList(),
    ) : MigrationResult()
}
