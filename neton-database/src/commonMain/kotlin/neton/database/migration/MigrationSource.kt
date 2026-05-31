package neton.database.migration

/**
 * 模块声明的 migration 来源(SPEC §0.3 最小形态)。
 *
 * 一个模块可以声明多份(例如三方言并存),engine 按 [MigrationConfig.dialect] 过滤。
 * 资源路径由模块**显式**声明,框架不硬编码 `<module>/sql/<dialect>/` 这种约定。
 *
 * @property moduleId 写入 history 表 `module_id` 列;同 application 内 module_id 唯一
 * @property dialect 此份 SQL 文件的方言;只有与 [MigrationConfig.dialect] 匹配的 source 才会被执行
 * @property resourcePath 文件系统路径(目录),engine 用 [MigrationScriptScanner] 扫描
 */
data class MigrationSource(
    val moduleId: String,
    val dialect: MigrationDialect,
    val resourcePath: String,
)
