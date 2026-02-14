package neton.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 按 cacheName 的配置。Redis key 前缀由 neton-redis RedisConfig.keyPrefix 统一管理。
 * v1 冻结：默认 ProtoBuf；JSON 仅调试、须显式开启；clear 使用 SCAN，KEYS 仅当 allowKeysClear=true 时降级并 WARN。
 */
data class CacheConfig(
    val name: String,
    /** 默认 PROTOBUF；JSON 仅用于调试、须显式开启 */
    val codec: CacheCodecKind = CacheCodecKind.PROTOBUF,
    val ttl: Duration = 1.hours,
    val nullTtl: Duration? = null,
    val maxSize: Int? = 1000,
    val enableL1: Boolean = true,
    /** 为 true 时允许 clear() 降级使用 KEYS（危险，生产禁用）；默认 false，优先 SCAN */
    val allowKeysClear: Boolean = false,
)
