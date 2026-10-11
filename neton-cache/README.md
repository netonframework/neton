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

清理保留 `cache:<name>:` 命名空间，每批最多删除 256 个键。SCAN 不是原子快照，
与清理并发写入的新值不保证被删除。

L1 读取会更新 LRU 次序，TTL 使用单调时钟。`nullTtl` 会阻止缓存空值时重复调用 loader。
当前 Redis GET 接口不返回剩余 TTL，因此 L2 命中不回填 L1；只有本地 put/loader
产生的值写入 L1，并扣除 L2 写入所耗时间，不能用一个完整的新 TTL 延长旧值寿命。
这是一项正确性取舍，后续可通过原子读取 value + 剩余 TTL 恢复安全回填。

同键 loader 的发起者被取消时，本次等待者也收到取消；singleflight 登记必定移除，
后续请求可以重新加载。独立等待者取消不会取消发起者。
