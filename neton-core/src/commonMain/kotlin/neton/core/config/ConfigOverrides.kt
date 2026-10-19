package neton.core.config

/**
 * ENV/CLI 覆盖（Neton-Core-Spec 5.4）。
 * ENV：NETON_ 前缀，__ 表示点，路径小写；合并时按忽略大小写对齐到配置里已有的键（见 [alignKeys]）。
 * CLI：--key=value，key 为点分路径。
 * 5.4.2：覆盖项必须被消费 —— 第一段必须是 application.conf 根段或模块命名空间，见 [findUnknownOverrides]。
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
        val withEnv = ConfigMerge.merge(base, alignKeys(base, envToOverrides(env)))
        return ConfigMerge.merge(withEnv, alignKeys(withEnv, cliToOverrides(args)))
    }

    /**
     * 把覆盖项的键名对齐到配置里已有的写法（忽略大小写）。
     *
     * 环境变量名只能是大写，[envToOverrides] 因此把路径统一转成小写；配置文件里却有驼峰键
     * （`[security.jwt] secretKey`）。不对齐的话，`NETON_SECURITY__JWT__SECRETKEY` 生成的是
     * `secretkey`，与 `secretKey` 并存，读取方拿到的仍是文件里的原值 —— 覆盖静默失效。
     * 配置里不存在的键保持原样（覆盖可以新增键）。
     */
    @Suppress("UNCHECKED_CAST")
    internal fun alignKeys(base: Map<String, Any?>, override: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in override) {
            val existing = base.keys.firstOrNull { it == key } ?: base.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            val target = existing ?: key
            val baseChild = existing?.let { base[it] }
            result[target] = if (value is Map<*, *> && baseChild is Map<*, *>) {
                alignKeys(baseChild as Map<String, Any?>, value as Map<String, Any?>)
            } else {
                value
            }
        }
        return result
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
        return ConfigMerge.merge(base, alignKeys(base, scoped))
    }

    // ---------- 5.4.2 覆盖必须被消费 ----------

    /**
     * 框架自己定义的 CLI 参数（不是配置路径）：`--env=prod` 选环境文件。
     * `NETON_ENV` 同理。
     */
    val FRAMEWORK_ARGS: Set<String> = setOf("env")

    /**
     * 框架会从 application.conf 读取的根段。文件里没写这些段时覆盖依然有意义
     * （`--server.port=9000` 对一份没有 `[server]` 的 application.conf 也要生效）。
     */
    val FRAMEWORK_APP_ROOTS: Set<String> = setOf("server", "http", "cors", "logging", "websocket", "application", "egress")

    /** CLI 中参与覆盖的项：(原始键写法 `--a.b`, 点分路径 `a.b`)。只认 `--key=value`；子命令、`--flag` 不参与。 */
    internal fun cliOverrideEntries(args: Array<String>): List<Pair<String, String>> =
        args.filter { it.startsWith("--") && it.contains("=") }
            .map { arg ->
                val key = arg.removePrefix("--").substringBefore("=")
                "--$key" to key
            }
            .filter { it.second.isNotEmpty() }

    /** ENV 中参与覆盖的项：(变量名, 小写点分路径)。 */
    internal fun envOverrideEntries(env: Map<String, String>): List<Pair<String, String>> =
        env.keys.filter { it.startsWith("NETON_") }
            .map { it to it.removePrefix("NETON_").replace("__", ".").lowercase() }
            .filter { it.second.isNotEmpty() }

    /**
     * 找出没有被任何配置消费的覆盖项（Neton-Core-Spec 5.4.2）。
     *
     * 覆盖项的第一段必须是一个**已知根**，否则它哪儿也落不到：
     * - application.conf（含环境文件）的根键 —— [appRoots]
     * - 框架读取的 application 根段 [FRAMEWORK_APP_ROOTS]、框架参数 [FRAMEWORK_ARGS]
     * - 模块命名空间：本进程已加载的 [namespaces]，或 [namespaceDeclared] 认定存在的
     *   （配置目录里有 `<ns>.conf` / `<ns>.<env>.conf`）
     *
     * 只校验第一段：模块里大量键有代码默认值、文件里不写，没有 schema 可对，
     * 深层校验只会误报。第一段错是这类 bug 的全部形状 —— 漏了命名空间前缀。
     *
     * 未知根如果恰好是某个模块文件里的段（`default` 之于 database.conf），给出带
     * 命名空间的正确写法作为 hint。
     */
    fun findUnknownOverrides(
        env: Map<String, String>,
        args: Array<String>,
        appRoots: Set<String>,
        namespaces: Map<String, Map<String, Any?>>,
        namespaceDeclared: (String) -> Boolean = { false },
    ): List<UnknownConfigOverride> {
        val known = (appRoots + FRAMEWORK_APP_ROOTS + FRAMEWORK_ARGS + namespaces.keys).map { it.lowercase() }.toSet()
        fun isKnown(root: String) = root.lowercase() in known || namespaceDeclared(root)

        val result = mutableListOf<UnknownConfigOverride>()
        fun check(source: ConfigSource, arg: String, path: String) {
            val root = path.substringBefore(".")
            if (isKnown(root)) return
            val owner = namespaces.entries.firstOrNull { (_, base) -> base.keys.any { it.equals(root, ignoreCase = true) } }?.key
            val hint = owner?.let {
                when (source) {
                    ConfigSource.CLI -> "--$it.$path"
                    else -> "NETON_" + "$it.$path".uppercase().replace(".", "__")
                }
            }
            result += UnknownConfigOverride(source, arg, path, root, hint)
        }
        // 不含 `__` 的 NETON_X 不当作配置路径检查：这类单段变量绝大多数是进程开关或密钥
        // （NETON_IO_DRIVER、NETON_WALLET_CARD_ENC_KEY），本来就不是覆盖项，报出来只是噪音。
        for ((name, path) in envOverrideEntries(env)) if ('.' in path) check(ConfigSource.ENV, name, path)
        for ((arg, path) in cliOverrideEntries(args)) check(ConfigSource.CLI, arg, path)
        return result
    }
}
