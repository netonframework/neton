package neton.core.module

/**
 * 单个 migration 脚本(已 embed 到 binary)。
 *
 * 由各模块 `build.gradle.kts` 的 `generateMigrationResources` task 从
 * `sql/<dialect>/V*.sql` 读取后生成 Kotlin 常量。运行期不读文件。
 *
 * @property version 版本号(无 `V` 前缀,如 `"001"`)。同 module 内 UNIQUE
 * @property description 脚本描述(`V<ver>__<desc>.sql` 中的 desc 部分)
 * @property content SQL 文件原始内容(脚本生成时已 Base64 编码 + 编译期解码)
 * @property checksum SHA-256 hex,生成时计算并固化(脚本变化 = 重新构建 binary)
 */
data class MigrationScript(
    val version: String,
    val description: String,
    val content: String,
    val checksum: String,
)
