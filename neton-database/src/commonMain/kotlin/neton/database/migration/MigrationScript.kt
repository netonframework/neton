package neton.database.migration

/**
 * 扫描得到的单个 migration 脚本元信息。
 *
 * 命名约定: `V<version>__<description>.sql`
 *   - V 大写前缀
 *   - version: 数字字符串(字典序 = 执行序;同 module 内 UNIQUE)
 *   - 双下划线分隔
 *   - description: snake_case
 */
data class MigrationScript(
    val moduleId: String,
    val version: String,
    val description: String,
    val fileName: String,
    val absolutePath: String,
    val content: String,
    val checksum: String,
)
