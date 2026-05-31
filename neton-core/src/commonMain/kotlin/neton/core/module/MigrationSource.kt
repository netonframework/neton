package neton.core.module

/**
 * 模块声明的 migration 来源 — **scripts 直接持有,已 embed 到 binary**(SPEC §0.3 / 2026-06-01)。
 *
 * 不再有 `resourcePath` 字段:K/N 单 binary 部署语义意味着 SQL 必须作为 Kotlin 字符串常量
 * 编译进 application.kexe,运行期不读 filesystem。每个模块的 `build.gradle.kts` 加
 * `generateMigrationResources` Gradle task 把 `sql/<dialect>/V*.sql` 转成 Kotlin 源文件,
 * `ModuleInitializer.migrations()` 返回 generated `<ModuleId>MigrationResources.sources`。
 *
 * @property moduleId 写入 history 表 `module_id` 列;同 application 内唯一。framework 把它当
 *   opaque namespace,不解释业务含义
 * @property dialect 此份 SQL 的方言;engine 按 application 当前 driver 过滤
 * @property scripts 已 embed 的脚本列表,按 version 升序(模块自管)
 */
data class MigrationSource(
    val moduleId: String,
    val dialect: MigrationDialect,
    val scripts: List<MigrationScript>,
)
