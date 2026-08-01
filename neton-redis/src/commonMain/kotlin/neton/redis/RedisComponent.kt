package neton.redis

import neton.core.Neton
import neton.core.component.NetonComponent
import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.logging.LoggerFactory
import neton.redis.lock.LockManager
import neton.redis.lock.RedisLockManager

/**
 * Redis 组件 - 只做连接配置，不写业务逻辑。
 * 绑定 RedisClient、LockManager 到 ctx，业务层 ctx.get(RedisClient::class) / ctx.get(LockManager::class)。
 */
object RedisComponent : NetonComponent<RedisSettings> {

    override fun defaultConfig(): RedisSettings = RedisSettings()

    override suspend fun init(ctx: NetonContext, config: RedisSettings) {
        val effective = mergeWithFile(ctx, config)
        val errors = effective.validate()
        if (errors.isNotEmpty()) throw RedisException("Redis config invalid: ${errors.joinToString(", ")}")
        val log = ctx.getOrNull(LoggerFactory::class)?.get("neton.redis")
        val client = DefaultRedisClient(effective, log)
        ctx.bind(RedisClient::class, client)
        ctx.bind(LockManager::class, RedisLockManager(client))
        if (effective.debug) {
            log?.info("Redis initialized", mapOf("host" to effective.host, "port" to effective.port, "database" to effective.database, "poolSize" to effective.poolSize))
        }
    }

    override suspend fun stop(ctx: NetonContext) {
        ctx.getOrNull(RedisClient::class)?.close()
    }

    /**
     * 文件名 = 命名空间：redis.conf → config.redis.*
     * 冻结：redis.conf 根级平铺（host/port 等），禁止 [redis]。
     */
    private fun mergeWithFile(ctx: NetonContext, dsl: RedisSettings): RedisConfig {
        val raw = ConfigLoader.loadModuleConfig("redis", configPath = "config", environment = ConfigLoader.resolveEnvironment(ctx.args), args = ctx.args)
        @Suppress("UNCHECKED_CAST")
        val redisSection = raw as? Map<String, Any>
        val fromFile = redisSection?.let { RedisSettings.fromMap(it) } ?: RedisSettings()
        return resolveRedisConfig(dsl, fromFile)
    }
}

/** 语法糖：redis { host = "127.0.0.1"; port = 6379; poolSize = 16; database = 0 } */
fun Neton.LaunchBuilder.redis(block: RedisSettings.() -> Unit) {
    install(RedisComponent, block)
}
