package neton.core.config

import neton.core.CoreLog

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
            val envMap = loadRawConfigFile(envPath) ?: return base
            ConfigMerge.merge(base, envMap)
        }
        return ConfigOverrides.applyOverrides(merged.toMutableMap(), getEnvMap(), args)
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
            val envMap = loadRawConfigFile("$configPath/application.$environment.conf") ?: return base
            ConfigMerge.merge(base, envMap)
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