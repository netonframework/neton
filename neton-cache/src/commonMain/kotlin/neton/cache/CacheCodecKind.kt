package neton.cache

/**
 * v1 冻结：缓存 value 序列化格式。
 * 默认 PROTOBUF；JSON 仅用于调试/排障，显式开启。
 */
enum class CacheCodecKind {
    /** 默认，性能优先 */
    PROTOBUF,
    /** 仅调试，显式开启；线上禁用 */
    JSON,
}
