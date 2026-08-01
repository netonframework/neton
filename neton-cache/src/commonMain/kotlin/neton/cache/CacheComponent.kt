package neton.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.logging.LoggerFactory
import neton.redis.RedisClient

/**
 * 单个命名缓存的声明。字段与 [CacheConfig] 一一对应。
 */
class CacheSpec internal constructor(private val name: String) {
    /** L2 序列化格式；默认 PROTOBUF，JSON 仅用于调试 */
    var codec: CacheCodecKind = CacheCodecKind.PROTOBUF

    /** 默认 TTL；`put(key, ttl = null)` 时使用它 */
    var ttl: Duration = CacheConfig(name).ttl

    /** 空值 TTL（防穿透）；null 表示不缓存 null */
    var nullTtl: Duration? = null

    /** L1 最大条目数（LRU）；null 表示不限制条目、仅按 TTL 淘汰 */
    var maxSize: Int? = 1000

    /** 是否启用进程内 L1 */
    var enableL1: Boolean = true

    /** clear() 允许降级用 KEYS（危险，生产禁用）；默认 false，优先 SCAN */
    var allowKeysClear: Boolean = false

    internal fun build(): CacheConfig = CacheConfig(
        name = name,
        codec = codec,
        ttl = ttl,
        nullTtl = nullTtl,
        maxSize = maxSize,
        enableL1 = enableL1,
        allowKeysClear = allowKeysClear,
    )
}

/**
 * `cache { }` 安装层配置。
 *
 * ```kotlin
 * Neton.run(args) {
 *     redis { }                       // cache 的 L2 依赖 redis，必须先装
 *     cache {
 *         cache("user") { ttl = 10.minutes; maxSize = 5000 }
 *         cache("session") { ttl = 30.minutes }
 *     }
 * }
 * ```
 *
 * 也可在 `config/cache.conf` 里声明（见 [CacheComponent]）；同名以 DSL 为准。
 */
class CacheSettings {
    internal val defined = mutableMapOf<String, CacheConfig>()

    var debug: Boolean = false

    /** 声明一个命名缓存。未声明的 name 在 `getCache(name)` 时报错。 */
    fun cache(name: String, block: CacheSpec.() -> Unit = {}) {
        require(name.isNotBlank()) { "cache name must not be blank" }
        defined[name] = CacheSpec(name).apply(block).build()
    }
}

/**
 * Cache 组件：把 [CacheManager] 绑进 [NetonContext]，让 `@Cacheable` / `@CachePut` / `@CacheEvict`
 * 的 KSP 织入代码能取到它。
 *
 * L2 强绑定 neton-redis，因此必须先安装 `redis { }`，否则启动即失败（不静默降级成只有 L1，
 * 那会让缓存在多实例部署下表现不一致）。
 *
 * 文件配置 `config/cache.conf`（文件名即命名空间，禁止再套 `[cache]`）：
 *
 * ```toml
 * debug = false
 *
 * [caches.user]
 * ttlMs = 600000
 * maxSize = 5000
 * enableL1 = true
 * codec = "PROTOBUF"      # 或 JSON（仅调试）
 * nullTtlMs = 60000
 * allowKeysClear = false
 * ```
 */
object CacheComponent : NetonComponent<CacheSettings> {

    override fun defaultConfig(): CacheSettings = CacheSettings()

    override suspend fun init(ctx: NetonContext, config: CacheSettings) {
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.cache")

        val redis = ctx.getOrNull(RedisClient::class)
            ?: error(
                "cache { } requires redis { }: the L2 tier is backed by neton-redis. " +
                    "Install redis { } before cache { } in your Neton.run block."
            )

        val fromFile = loadFromFile(ctx)
        // DSL 显式声明的同名缓存覆盖文件声明
        val effective = fromFile + config.defined

        if (effective.isEmpty()) {
            log?.warn(
                "cache.no_caches_declared",
                mapOf("hint" to "declare caches via cache(\"name\") { } or config/cache.conf [caches.<name>]"),
            )
        }

        ctx.bind(CacheManager::class, DefaultCacheManager(redis, effective))

        if (config.debug || effective.isNotEmpty()) {
            log?.info(
                "cache.initialized",
                mapOf("caches" to effective.keys.sorted().joinToString(","), "count" to effective.size),
            )
        }
    }

    /**
     * 读 `config/cache.conf` 的 `[caches.<name>]` 段。文件不存在时返回空表（纯 DSL 声明是合法用法）。
     */
    private fun loadFromFile(ctx: NetonContext): Map<String, CacheConfig> {
        val raw = ConfigLoader.loadModuleConfig(
            "cache",
            configPath = "config",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args,
        ) ?: return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val caches = raw["caches"] as? Map<String, Any> ?: return emptyMap()

        return caches.mapNotNull { (name, node) ->
            @Suppress("UNCHECKED_CAST")
            val m = node as? Map<String, Any> ?: return@mapNotNull null
            name to cacheConfigFromMap(name, m)
        }.toMap()
    }

    internal fun cacheConfigFromMap(name: String, m: Map<String, Any>): CacheConfig {
        val defaults = CacheConfig(name)
        val codec = (m["codec"] as? String)?.let { raw ->
            CacheCodecKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown cache codec '$raw' for cache '$name' " +
                        "(valid: ${CacheCodecKind.entries.joinToString { it.name }})"
                )
        } ?: defaults.codec

        // maxSize <= 0 表示不限制条目（仅按 TTL 淘汰）；写了但不是数字直接报错，
        // 不静默当成「不限制」——那会让一个 typo 变成无界内存增长。
        val maxSize = if (m.containsKey("maxSize")) {
            val n = (m["maxSize"] as? Number)?.toInt()
                ?: throw IllegalArgumentException(
                    "cache '$name': maxSize must be a number (use 0 or a negative value for unbounded), got '${m["maxSize"]}'"
                )
            if (n <= 0) null else n
        } else {
            defaults.maxSize
        }

        return CacheConfig(
            name = name,
            codec = codec,
            ttl = numberOrThrow(m, "ttlMs", name)?.milliseconds ?: defaults.ttl,
            nullTtl = numberOrThrow(m, "nullTtlMs", name)?.milliseconds ?: defaults.nullTtl,
            maxSize = maxSize,
            enableL1 = booleanOrThrow(m, "enableL1", name) ?: defaults.enableL1,
            allowKeysClear = booleanOrThrow(m, "allowKeysClear", name) ?: defaults.allowKeysClear,
        )
    }

    private fun numberOrThrow(m: Map<String, Any>, key: String, cacheName: String): Long? {
        if (!m.containsKey(key)) return null
        return (m[key] as? Number)?.toLong()
            ?: throw IllegalArgumentException("cache '$cacheName': $key must be a number, got '${m[key]}'")
    }

    private fun booleanOrThrow(m: Map<String, Any>, key: String, cacheName: String): Boolean? {
        if (!m.containsKey(key)) return null
        return m[key] as? Boolean
            ?: throw IllegalArgumentException("cache '$cacheName': $key must be true/false, got '${m[key]}'")
    }
}

/**
 * 语法糖：`cache { cache("user") { ttl = 10.minutes } }`
 *
 * 必须在 `redis { }` 之后安装。
 */
fun Neton.LaunchBuilder.cache(block: CacheSettings.() -> Unit = {}) {
    install(CacheComponent, block)
}
