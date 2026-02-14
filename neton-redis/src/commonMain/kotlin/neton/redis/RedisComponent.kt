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
object RedisComponent : NetonComponent<RedisConfig> {

    override fun defaultConfig(): RedisConfig = RedisConfig()

    override suspend fun init(ctx: NetonContext, config: RedisConfig) {
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

    /**
     * 文件名 = 命名空间：redis.conf → config.redis.*
     * 冻结：redis.conf 根级平铺（host/port 等），禁止 [redis]。
     */
    private fun mergeWithFile(ctx: NetonContext, dsl: RedisConfig): RedisConfig {
        val raw = ConfigLoader.loadModuleConfig("redis", configPath = "config", environment = ConfigLoader.resolveEnvironment(ctx.args), args = ctx.args) ?: return dsl
        @Suppress("UNCHECKED_CAST")
        val redisSection = raw as? Map<String, Any> ?: return dsl
        val fromFile = RedisConfig.fromMap(redisSection)
        return RedisConfig(
            host = dsl.host.ifBlank { fromFile.host },
            port = if (dsl.port != 6379 || fromFile.port != 6379) dsl.port else fromFile.port,
            poolSize = if (dsl.poolSize != 16) dsl.poolSize else fromFile.poolSize,
            database = if (dsl.database != 0) dsl.database else fromFile.database,
            password = dsl.password ?: fromFile.password,
            timeoutMs = if (dsl.timeoutMs != 5000L) dsl.timeoutMs else fromFile.timeoutMs,
            debug = dsl.debug || fromFile.debug
        )
    }
}

/** 语法糖：redis { host = "127.0.0.1"; port = 6379; poolSize = 16; database = 0 } */
fun Neton.LaunchBuilder.redis(block: RedisConfig.() -> Unit) {
    install(RedisComponent, block)
}
