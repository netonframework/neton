package neton.core.config

import neton.core.CoreLog
import kotlin.concurrent.AtomicReference

/**
 * 配置加载器 - Core 模块纯文件加载器
 *
 * 职责：
 * 1. 根据文件名加载原始配置内容
 * 2. 支持环境配置覆盖（dev/test/prod）
 * 3. 统一错误处理和日志记录
 * 4. 不关心具体配置格式，只负责文件 I/O
 *
 * 模块自治：每个模块自己解析配置内容
 *
 * ## 冻结规则：文件名 = 命名空间
 *
 * - database.conf → config.database.*（禁止 [database] 段） root table 仅允许连接名 [default]/[analytics] 等
 * - redis.conf → config.redis.*（禁止 [redis] 段） 根级平铺 host/port 等
 * - routing.conf → config.routing.*（禁止 [routing] 段） 根级平铺 debug/groups
 *
 * 读取路径：config.database.default.driver、config.redis.host、config.routing.debug。
 */
object ConfigLoader {
    
    private const val DEFAULT_CONFIG_PATH = "config"

    /**
     * 本进程加载过的模块命名空间 → 文件内容（合并环境文件后、应用覆盖前）。
     * 用于 [validateOverrides] 判定命名空间存在，以及给漏前缀的覆盖推断正确写法。
     */
    private val loadedNamespaces = AtomicReference<Map<String, Map<String, Any?>>>(emptyMap())

    private fun recordNamespace(namespace: String, content: Map<String, Any?>) {
        while (true) {
            val current = loadedNamespaces.value
            if (loadedNamespaces.compareAndSet(current, current + (namespace to content))) return
        }
    }
    
    /**
     * 从启动参数 --env=xxx 或 ENV 解析 environment，默认 dev。
     * 用于加载 application.{env}.conf 及 banner 显示。
     */
    fun resolveEnvironment(args: Array<String> = emptyArray()): String {
        val fromCli = ConfigOverrides.cliToOverrides(args)["env"] as? String
        if (!fromCli.isNullOrBlank()) return fromCli.trim()
        val fromEnv = getEnv("NETON_ENV") ?: getEnv("ENV") ?: getEnv("NODE_ENV")
        return fromEnv?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
    }
    
    /**
     * 根据模块名加载配置：base + env 文件合并后，再应用 ENV/CLI overrides（5.1/5.3/5.4）。
     * Overrides 在 Loader 内收口，调用方无需记忆 applyOverrides。
     * 解析失败抛 ConfigParseException；类型错误在 typed getter 时抛 ConfigTypeException。
     */
    fun loadModuleConfig(
        moduleName: String,
        configPath: String = DEFAULT_CONFIG_PATH,
        environment: String? = null,
        args: Array<String> = emptyArray()
    ): Map<String, Any?>? {
        val configFileName = moduleNameToConfigFile(moduleName)
        val basePath = "$configPath/$configFileName"
        val base = try {
            loadRawConfigFile(basePath) ?: emptyMap()
        } catch (e: ConfigParseException) {
            throw e
        } catch (e: Exception) {
            CoreLog.logOrBootstrap().warn("neton.config.load.failed", mapOf("module" to moduleName, "message" to (e.message ?: "")))
            return null
        }
        val merged = if (environment == null) base else run {
            val baseName = configFileName.removeSuffix(".conf")
            val envPath = "$configPath/$baseName.$environment.conf"
            loadRawConfigFile(envPath)?.let { ConfigMerge.merge(base, it) } ?: base
        }
        // 命名空间取文件名（DataModule → database.conf → "database"），不是传入的 moduleName。
        val namespace = configFileName.removeSuffix(".conf")
        recordNamespace(namespace, merged)
        rejectOverridesMissingThisNamespace(namespace, merged, configPath, environment, args)
        return ConfigOverrides.applyModuleOverrides(namespace, merged.toMutableMap(), getEnvMap(), args)
    }

    /**
     * 加载模块时就拦下最常见的错误：CLI 覆盖漏了命名空间前缀，而第一段恰好是本模块文件里的段
     * （`--default.uri=...` 之于 database.conf 的 `[default]`）。
     *
     * 不能只靠启动末尾的 [validateOverrides]：`migrate` 子命令和启动前 precheck 不走 `Neton.run`，
     * 只加载 database.conf 就去连库 —— 漏前缀的 uri 被忽略，连上的是默认开发库。
     */
    private fun rejectOverridesMissingThisNamespace(
        namespace: String,
        content: Map<String, Any?>,
        configPath: String,
        environment: String?,
        args: Array<String>,
    ) {
        val suspects = ConfigOverrides.cliOverrideEntries(args).filter { (_, path) ->
            val root = path.substringBefore(".")
            !root.equals(namespace, ignoreCase = true) && content.keys.any { it.equals(root, ignoreCase = true) }
        }
        if (suspects.isEmpty()) return
        val unknown = ConfigOverrides.findUnknownOverrides(
            env = emptyMap(),
            args = suspects.map { (arg, _) -> args.first { it.startsWith("$arg=") } }.toTypedArray(),
            appRoots = rawApplicationRoots(configPath, environment),
            namespaces = loadedNamespaces.value,
            // 此刻别的模块可能还没加载：框架组件的命名空间一律算已知，宁可漏报不误报，
            // 启动末尾的 validateOverrides 会再按实际装配严格校验一遍。
            namespaceDeclared = {
                it.lowercase() in FRAMEWORK_MODULE_NAMESPACES || namespaceFileExists(it, configPath, environment)
            },
        )
        if (unknown.isNotEmpty()) failOnUnknownOverrides(unknown)
    }

    /**
     * 5.4.2：每个 CLI `--key=value` 与每个 `NETON_*` 环境变量都必须落到某份配置上。
     *
     * 在全部配置加载完之后调用（`Neton.run` 在组件 init/prepare 与用户块之后调用一次）。
     * - CLI 未知根 → 打 `neton.config.override.unknown` 错误日志并抛 [ConfigOverrideException]（启动失败）
     * - ENV 未知根 → 打 `neton.config.override.unused` 告警，不失败（共享 shell 里的变量可能属于别的宿主）
     */
    fun validateOverrides(
        configPath: String = DEFAULT_CONFIG_PATH,
        environment: String? = null,
        args: Array<String> = emptyArray(),
        env: Map<String, String> = getEnvMap(),
    ) {
        val unknown = ConfigOverrides.findUnknownOverrides(
            env = env,
            args = args,
            appRoots = rawApplicationRoots(configPath, environment),
            namespaces = loadedNamespaces.value,
            namespaceDeclared = { namespaceFileExists(it, configPath, environment) },
        )
        val log = CoreLog.logOrBootstrap()
        for (u in unknown.filter { it.source == ConfigSource.ENV }) {
            log.warn("neton.config.override.unused", overrideFields(u))
        }
        val cli = unknown.filter { it.source == ConfigSource.CLI }
        if (cli.isNotEmpty()) failOnUnknownOverrides(cli)
    }

    private fun failOnUnknownOverrides(unknown: List<UnknownConfigOverride>): Nothing {
        val log = CoreLog.logOrBootstrap()
        for (u in unknown) log.error("neton.config.override.unknown", overrideFields(u))
        exitOnUncaughtConfigOverrideError()
        throw ConfigOverrideException(unknown)
    }

    private fun overrideFields(u: UnknownConfigOverride): Map<String, Any?> = buildMap {
        put("code", ConfigOverrideException.CODE)
        put("source", u.source.name)
        put("arg", u.arg)
        put("root", u.root)
        put(
            "hint",
            u.hint ?: when (u.source) {
                ConfigSource.ENV -> "NETON_<NAMESPACE>__<KEY> for module config, or NETON_<SECTION>__<KEY> for application.conf"
                else -> "--<namespace>.<key>=... for module config, or --<section>.<key>=... for application.conf"
            },
        )
    }

    /** application.conf（+ 环境文件）的根键，不含覆盖本身新增的键。 */
    private fun rawApplicationRoots(configPath: String, environment: String?): Set<String> {
        val base = loadRawConfigFile("$configPath/application.conf")?.keys ?: emptySet()
        val envKeys = environment?.let { loadRawConfigFile("$configPath/application.$it.conf")?.keys } ?: emptySet()
        return base + envKeys
    }

    /** 框架组件自己加载的模块命名空间（没有配置文件时也会用代码默认值加载）。 */
    private val FRAMEWORK_MODULE_NAMESPACES = setOf("database", "redis", "cache", "storage", "jobs", "routing", "ai")

    private val NAMESPACE_NAME = Regex("^[A-Za-z0-9_-]+$")

    /** 配置目录里有 `<ns>.conf` 或 `<ns>.<env>.conf` —— 模块存在但本进程尚未（懒）加载它。 */
    private fun namespaceFileExists(root: String, configPath: String, environment: String?): Boolean {
        if (!NAMESPACE_NAME.matches(root)) return false
        val name = root.lowercase()
        if (name == "application") return false
        if (readConfigFile("$configPath/$name.conf") != null) return true
        return environment != null && readConfigFile("$configPath/$name.$environment.conf") != null
    }

    /**
     * 根据模块名生成配置文件名（Neton-Core-Spec 5.1：TOML only，&lt;module&gt;.conf）。
     * - "RoutingModule" -> "routing.conf"
     * - "DataModule" -> "database.conf"
     */
    private fun moduleNameToConfigFile(moduleName: String): String {
        return when (moduleName.lowercase()) {
            "routingmodule" -> "routing.conf"
            "securitymodule" -> "security.conf"
            "datamodule" -> "database.conf"
            "httpmodule" -> "http.conf"
            "redismodule" -> "redis.conf"
            else -> {
                val configName = moduleName.removeSuffix("Module").lowercase()
                "$configName.conf"
            }
        }
    }

    /**
     * 加载原始配置文件：读文件 → TOML 解析（5.1/5.3）。
     * 文件不存在 → null；文件存在但解析失败 → 抛 ConfigParseException（fail-fast）。
     */
    private fun loadRawConfigFile(configFilePath: String): Map<String, Any?>? {
        val content = readConfigFile(configFilePath) ?: return null
        return TomlParser.parse(content, configFilePath)
    }
    
    /**
     * 加载应用程序主配置：application.conf + env 合并 + ENV/CLI overrides 收口。
     */
    fun loadApplicationConfig(
        configPath: String = DEFAULT_CONFIG_PATH,
        environment: String? = null,
        args: Array<String> = emptyArray()
    ): Map<String, Any?>? {
        val base = loadRawConfigFile("$configPath/application.conf") ?: emptyMap()
        val merged = if (environment == null) base else run {
            loadRawConfigFile("$configPath/application.$environment.conf")
                ?.let { ConfigMerge.merge(base, it) }
                ?: base
        }
        return ConfigOverrides.applyOverrides(merged.toMutableMap(), getEnvMap(), args)
    }

    /**
     * 按点分路径获取嵌套配置值（5.1.2）。
     */
    fun getConfigValue(config: Map<out String, Any?>?, path: String, defaultValue: Any? = null): Any? {
        if (config == null) return defaultValue
        val keys = path.split(".")
        var current: Any? = config
        for (key in keys) {
            when (current) {
                is Map<*, *> -> current = current[key]
                else -> return defaultValue
            }
        }
        return current ?: defaultValue
    }

    /**
     * 类型化读取：类型不匹配时抛 ConfigTypeException（5.4 fail-fast）。
     * @param source 错误来源（FILE/ENV/CLI），用于报错信息。
     */
    fun getInt(config: Map<out String, Any?>?, path: String, source: ConfigSource = ConfigSource.FILE): Int {
        val raw = getConfigValue(config, path) ?: throw ConfigTypeException(path, "Int", null, source)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: throw ConfigTypeException(path, "Int", raw, source)
            else -> throw ConfigTypeException(path, "Int", raw, source)
        }
    }

    fun getString(config: Map<out String, Any?>?, path: String, source: ConfigSource = ConfigSource.FILE): String? {
        val raw = getConfigValue(config, path) ?: return null
        return when (raw) {
            is String -> raw
            else -> raw.toString()
        }
    }

    fun getBoolean(config: Map<out String, Any?>?, path: String, source: ConfigSource = ConfigSource.FILE): Boolean {
        val raw = getConfigValue(config, path) ?: throw ConfigTypeException(path, "Boolean", null, source)
        return when (raw) {
            is Boolean -> raw
            is String -> when (raw.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw ConfigTypeException(path, "Boolean", raw, source)
            }
            else -> throw ConfigTypeException(path, "Boolean", raw, source)
        }
    }
    
    fun hasConfig(config: Map<out String, Any?>?, path: String): Boolean =
        getConfigValue(config, path) != null
    
}
