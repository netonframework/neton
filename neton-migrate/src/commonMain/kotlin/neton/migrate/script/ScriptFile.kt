package neton.migrate.script

/**
 * 脚本文件元信息。命名约定：V<version>__<description>.sql
 *   - V 大写前缀
 *   - version: 数字，按字典序 = 执行序
 *   - 双下划线分隔
 *   - description: snake_case
 */
data class ScriptFile(
    val version: String,
    val description: String,
    val fileName: String,
    val absolutePath: String,
    val content: String,
    val checksum: String
)
