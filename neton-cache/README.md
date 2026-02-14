# neton-cache

Neton 统一缓存抽象（v1）：L1 本地 LRU+TTL + L2 强绑定 neton-redis，用户不关心层级。

- **规范**：见 `neton-docs/Neton-Cache-Spec-v1.md`（设计冻结）
- **API**：`Cache`、`CacheManager`、`CacheConfig`（codec=PROTOBUF/JSON，allowKeysClear；前缀由 neton-redis 统一管理）
- **实现**：TwoLevelCache + L1Cache + RedisCacheBacking；默认 **ProtoBuf**，可选 JSON（仅调试）；value 带 MAGIC+CODEC header（9.1）；clear 优先 **SCAN**，KEYS 仅 allowKeysClear 降级+WARN
- **依赖**：neton-core、neton-redis

## 使用

```kotlin
val configs = mapOf("user" to CacheConfig(name = "user", ttl = 1.hours))
val manager = DefaultCacheManager(redis, configs)
val cache = manager.getCache<User>("user")  // suspend
val u = cache.getOrPut("1") { userRepo.findById(1) }
cache.put("2", user2)
cache.delete("1")
cache.clear()
```

## L2 clear()

优先 **SCAN**（MATCH prefix:* COUNT N + pipeline DEL）。仅当 **allowKeysClear=true** 时允许降级 KEYS（危险，生产禁用）；实现会 WARN。
