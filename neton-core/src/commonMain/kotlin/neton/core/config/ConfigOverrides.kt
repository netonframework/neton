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
     */
    fun applyOverrides(
        base: MutableMap<String, Any?>,
        env: Map<String, String>,
        args: Array<String>
    ): Map<String, Any?> {
        val withEnv = ConfigMerge.merge(base, envToOverrides(env))
        return ConfigMerge.merge(withEnv, cliToOverrides(args))
    }
}
