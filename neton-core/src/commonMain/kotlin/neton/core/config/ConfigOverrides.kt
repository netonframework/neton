package neton.core.config

/**
 * ENV/CLI 覆盖（Neton-Core-Spec 5.4）。
 * ENV：NETON_ 前缀，__ 表示点，路径小写。
 * CLI：--key=value，key 为点分路径。
 */
object ConfigOverrides {

    private fun pathToNested(path: String, value: Any): Map<String, Any?> {
        val parts = path.split(".")
        var current: Any = value
        for (i in parts.indices.reversed()) {
            current = mapOf(parts[i] to current)
        }
        @Suppress("UNCHECKED_CAST")
        return current as Map<String, Any?>
    }

    /**
     * 将 ENV Map 转为可合并的嵌套 Map。仅处理 NETON_ 前缀，__ → 点，路径小写。
     */
    fun envToOverrides(env: Map<String, String>): Map<String, Any?> {
        var result = emptyMap<String, Any?>()
        for ((key, value) in env) {
            if (!key.startsWith("NETON_")) continue
            val path = key.removePrefix("NETON_").replace("__", ".").lowercase()
            result = ConfigMerge.merge(result, pathToNested(path, value))
        }
        return result
    }

    /**
     * 将 CLI args 转为可合并的嵌套 Map。仅处理 --key=value 形式。
     */
    fun cliToOverrides(args: Array<String>): Map<String, Any?> {
        var result = emptyMap<String, Any?>()
        for (arg in args) {
            if (!arg.startsWith("--") || !arg.contains("=")) continue
            val (key, value) = arg.removePrefix("--").split("=", limit = 2)
            result = ConfigMerge.merge(result, pathToNested(key, value))
        }
        return result
    }

    /**
     * 对 base 先合并 ENV 再合并 CLI（优先级 CLI > ENV）。
     *
     * 用于 application.conf —— 主配置没有命名空间前缀，`NETON_SECURITY__JWT__SECRETKEY`
     * 直接对应文件里的 `[security.jwt] secretKey`。模块配置不要用这个，见
     * [applyModuleOverrides]。
     */
    fun applyOverrides(
        base: MutableMap<String, Any?>,
        env: Map<String, String>,
        args: Array<String>
    ): Map<String, Any?> {
        val withEnv = ConfigMerge.merge(base, envToOverrides(env))
        return ConfigMerge.merge(withEnv, cliToOverrides(args))
    }

    /**
     * 模块配置的覆盖：**只取属于本命名空间的那一支**。
     *
     * 模块配置文件是根级平铺的（文件名 = 命名空间：redis.conf 根级写 host/port，
     * database.conf 根级写 `[default]`），而 ENV 变量带命名空间前缀
     * （`NETON_REDIS__HOST` → `redis.host`）。所以要先剥掉这一层再合并。
     *
     * 用 [applyOverrides] 的全量合并会**串味**：`NETON_DATABASE__URI` 生成的
     * `database = {uri: ...}` 会落进每一个模块的配置里。redis 正好也有个 `database`
     * 字段（选库号，Int），于是拿到一个 Map —— 生产环境上就是这样启动崩溃的：
     * `redis.conf: 'database' must be a number, got '{uri=postgresql://...}'`。
     * 这个污染一直存在，只是以前被宽容解析静默吞掉，改成 fail-fast 后才炸出来。
     *
     * @param namespace 模块命名空间，取配置文件名去掉 `.conf`（不是传入的 moduleName ——
     *   `DataModule` 映射到 database.conf，命名空间是 `database`）。
     */
    fun applyModuleOverrides(
        namespace: String,
        base: MutableMap<String, Any?>,
        env: Map<String, String>,
        args: Array<String>
    ): Map<String, Any?> {
        val all = ConfigMerge.merge(envToOverrides(env), cliToOverrides(args))
        @Suppress("UNCHECKED_CAST")
        val scoped = all[namespace.lowercase()] as? Map<String, Any?> ?: return base
        return ConfigMerge.merge(base, scoped)
    }
}
