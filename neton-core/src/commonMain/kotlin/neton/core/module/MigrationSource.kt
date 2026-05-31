package neton.core.module

/**
 * 模块声明的 migration 来源(SPEC §0.3 最小形态)。
 *
 * @property moduleId 写入 history 表 `module_id` 列;同 application 内 module_id 唯一。
 *                   framework 把它当 opaque namespace,不解释业务含义。
 * @property dialect 此份 SQL 文件的方言;只有与 application 当前 driver 匹配的 source 才执行。
 * @property resourcePath 文件系统路径(目录),engine 在迁移时扫描。**相对/绝对**由 caller 解读;
 *                       SPEC §0.3 推荐传相对路径如 `"sql/postgresql"`,application Main 负责把
 *                       它解析成 engine 能读到的路径。
 */
data class MigrationSource(
    val moduleId: String,
    val dialect: MigrationDialect,
    val resourcePath: String,
)
