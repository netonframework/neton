package neton.migrate.cli

/**
 * 退出码契约 — 与 spec §5.4 一致
 * 见 https://netonframework.github.io/spec/migration
 */
object ExitCode {
    /** 全部成功 / 无需执行 / 全部已执行 / 校验全一致 */
    const val OK = 0

    /** status: 有未执行脚本（CI dry-run 用） */
    const val PENDING = 1

    /** up: 执行中失败 */
    const val EXECUTION_FAILED = 2

    /** checksum 校验失败 / history 表不存在（verify 时） */
    const val CHECKSUM_MISMATCH = 3

    /** 数据库连接失败 */
    const val DB_CONNECT_FAILED = 4

    /** 命令行参数错误 */
    const val USAGE_ERROR = 64
}
