package neton.database.migration

import neton.core.module.MigrationDialect

/**
 * Migration engine 运行时配置。
 *
 * 按 SPEC §0.4 / §六: history table 名称由 application config 决定,
 * 框架提供默认值 [DEFAULT_HISTORY_TABLE]。所有 DDL / query 必须使用
 * [historyTable] 字段,不许散落硬编码字符串。
 *
 * dialect 反映当前 application 链接的 sqlx4k driver(NETON-DB-VARIANT),
 * engine 据此选择 history 表 DDL 与扫描时过滤 [MigrationSource.dialect]。
 *
 * # Caller contract (DB-MIG-2)
 *
 * **生产 application 入口 (DB-MIG-4) 必须显式从 `database.conf` `[migration]`
 * 段读取 [historyTable],不允许走 default**。default 值的存在仅为:
 *   - 测试夹具/单元测试便利
 *   - 早期 Neton 项目脚手架默认
 *
 * `./application.kexe migrate` 启动期应检查 caller 是否提供 [historyTable] 配置,
 * 缺省 + 默认值仅在 dev/test fixture 路径下允许。这条约束由 application 层实施,
 * engine 不感知 default-vs-explicit 的区别。
 */
data class MigrationConfig(
    val dialect: MigrationDialect,
    val historyTable: String = DEFAULT_HISTORY_TABLE,
) {
    companion object {
        const val DEFAULT_HISTORY_TABLE: String = "neton_schema_history"
    }
}
