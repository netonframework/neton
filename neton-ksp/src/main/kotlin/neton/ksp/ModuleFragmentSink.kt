package neton.ksp

import java.util.concurrent.ConcurrentHashMap

/**
 * KSP 处理器间的内存片段共享中心（per-moduleId 隔离）
 *
 * 当 moduleId 存在时，各 Processor（Controller、Validation、Job、Config、Repository）
 * 把自己的注册代码片段写入此 sink，而不是独立生成文件。
 * ModuleInitializerProcessor 最后从 sink 读取所有片段，聚合成唯一的 {Id}ModuleInitializer。
 *
 * 由于同一编译单元内的所有 KSP Processor 运行在同一 JVM 进程中，
 * 使用 ConcurrentHashMap<moduleId, SinkData> 保证：
 * - 同一 moduleId 内各 Processor 共享状态
 * - 不同 moduleId 的 Gradle 子项目并行编译时互不干扰
 */
object ModuleFragmentSink {

    /** 每个 moduleId 独立的 sink 数据 */
    private class SinkData {
        val imports = mutableSetOf<String>()
        val fragments = mutableListOf<Fragment>()
        val topLevelDeclarations = mutableListOf<String>()
        val stats = mutableMapOf<String, Int>()
    }

    private val sinks = ConcurrentHashMap<String, SinkData>()

    data class Fragment(
        val domain: String,     // "routes", "validators", "repositories", "jobs", "configs"
        val comment: String,    // 注释
        val code: String        // initialize() 方法体内的代码
    )

    private fun sink(moduleId: String): SinkData =
        sinks.getOrPut(moduleId) { SinkData() }

    fun addImport(moduleId: String, import: String) {
        sink(moduleId).imports.add(import)
    }

    fun addImports(moduleId: String, vararg lines: String) {
        sink(moduleId).imports.addAll(lines)
    }

    fun addFragment(moduleId: String, domain: String, comment: String, code: String) {
        sink(moduleId).fragments.add(Fragment(domain, comment, code))
    }

    fun addTopLevelDeclaration(moduleId: String, code: String) {
        sink(moduleId).topLevelDeclarations.add(code)
    }

    fun getImports(moduleId: String): Set<String> =
        sinks[moduleId]?.imports?.toSet() ?: emptySet()

    fun getFragments(moduleId: String): List<Fragment> =
        sinks[moduleId]?.fragments?.toList() ?: emptyList()

    fun getTopLevelDeclarations(moduleId: String): List<String> =
        sinks[moduleId]?.topLevelDeclarations?.toList() ?: emptyList()

    fun addStat(moduleId: String, domain: String, count: Int) {
        sink(moduleId).stats[domain] = count
    }

    fun getStats(moduleId: String): Map<String, Int> =
        sinks[moduleId]?.stats?.toMap() ?: emptyMap()

    fun hasFragments(moduleId: String): Boolean =
        sinks[moduleId]?.fragments?.isNotEmpty() == true

    /** 指定 moduleId 编译结束后清空（由 ModuleInitializerProcessor 调用） */
    fun clear(moduleId: String) {
        sinks.remove(moduleId)
    }
}
