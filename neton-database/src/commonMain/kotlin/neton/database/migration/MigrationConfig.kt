package neton.database.migration

/**
 * Migration engine 运行时配置。
 *
 * 按 SPEC §0.4 / §六: history table 名称由 application config 决定,
 * 框架提供默认值 [DEFAULT_HISTORY_TABLE]。所有 DDL / query 必须使用
 * [historyTable] 字段,不许散落硬编码字符串。
 *
 * dialect 反映当前 application 链接的 sqlx4k driver(NETON-DB-VARIANT),
 * engine 据此选择 history 表 DDL 与扫描时过滤 [MigrationSource.dialect]。
 */
data class MigrationConfig(
    val dialect: MigrationDialect,
    val historyTable: String = DEFAULT_HISTORY_TABLE,
) {
    companion object {
        const val DEFAULT_HISTORY_TABLE: String = "neton_schema_history"
    }
}
