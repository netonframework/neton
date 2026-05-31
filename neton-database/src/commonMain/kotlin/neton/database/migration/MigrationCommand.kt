package neton.database.migration

/**
 * Engine 支持的命令(SPEC §5.1 最小集): `status` / `up` / `verify`。
 *
 * Engine 不承诺 `down`(SPEC §5.2)。
 */
enum class MigrationCommand {
    /** 只读: 列已应用 / 待应用 / changed. */
    STATUS,

    /** 顺序执行所有 pending,任一失败中断. */
    UP,

    /** 只读: 校验 history 表中已执行脚本的 checksum 与磁盘是否一致. */
    VERIFY,
}
