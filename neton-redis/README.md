# Neton Redis 模块

基于 **re.this**（Kotlin Multiplatform Redis 客户端）的极简 Redis 封装，Laravel/Ktor 风格 API，业务层零侵入、不暴露底层驱动。

## 设计原则

- **极简 API**：`get` / `set` / `delete` / `remember` / `pipeline`，无 Template/Ops 概念。
- **类型安全**：扩展方法 `redis.get<User>("key")`、`redis.remember<User>("key", ttl) { ... }`，基于 kotlinx.serialization JSON。
- **后端唯一**：实现仅依赖 [re.this](https://github.com/vendelieu/re.this) 0.3.9（协程、RESP、连接池）。

## 快速开始

### 1. 依赖

```kotlin
implementation(project(":neton-redis"))
// 或
implementation("your.group:neton-redis:version")
```

### 2. 配置

**方式一：配置文件** `config/redis.conf`（可由 Neton ConfigLoader 加载）：

```toml
[redis]
host = "localhost"
port = 6379
password = ""
database = 0
poolSize = 16
timeout = 5000
```

**方式二：DSL**（可覆盖文件配置）：

```kotlin
import neton.core.Neton
import neton.http.http
import neton.routing.routing
import neton.redis.redis

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        routing { }
        redis {
            host = "127.0.0.1"
            port = 6379
            database = 0
            poolSize = 16
        }
        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```

### 3. 获取客户端

```kotlin
// 从 Neton 上下文（在 onStart、Controller 等具备 ctx 的场景）
val redis = ctx.getRedis()
// 或
val redis = ctx.get<RedisClient>()

// 在 Neton.run { } 作用域内
val redis = NetonContext.current().get<RedisClient>()
```

### 4. 使用示例

```kotlin
// 原始字符串
redis.set("key", "value")
redis.set("key", "value", ttl = 5.minutes)
val raw: String? = redis.get("key")           // 扩展：等价于 getValue(key)
val raw2: String? = redis.getValue("key")    // 接口方法

// 类型安全（扩展：反序列化 JSON / 基本类型）
val user: User? = redis.get<User>("user:1")
redis.set("user:1", user)

// Laravel 风格 remember：有缓存则反序列化返回，否则执行 block 并写入缓存
val u: User = redis.remember("user:$id", 5.minutes) { UserTable.get(id) }

// Pipeline（批量命令，减少 RTT）
redis.pipeline {
    set("a", "1")
    set("b", "2")
    incr("counter")
}

// Hash / List / Set
redis.hset("user:1001", "name", "张三")
val map = redis.hgetAll("user:1001")
redis.rpush("list", "item")
val list = redis.lrange("list", 0, -1)
redis.sadd("set", "member")
val set = redis.smembers("set")
```

## API 概览

| 类别     | 方法 | 说明 |
|----------|------|------|
| KV       | `getValue(key)` | 原始 String? |
|         | `get(key)` / `get<T>(key)` | 扩展：String? 或类型安全 T? |
|         | `set(key, value, ttl?)` | value: Any，支持 Duration TTL |
|         | `delete(key)` / `exists(key)` / `expire(key, ttl)` | |
|         | `incr(key)` / `decr(key)` | |
| Hash     | `hset` / `hget` / `hgetAll` | |
| List     | `lpush` / `rpush` / `lpop` / `lrange(start, end)` | |
| Set      | `sadd` / `smembers` | |
| Cache    | `remember<T>(key, ttl) { block }` | 扩展：先读缓存再解码，否则 block + set |
| Pipeline | `pipeline { set(...); incr(...); ... }` | 块内排队，块结束顺序执行 |

## 架构简述

- **RedisClient**：统一接口，仅定义能力，不暴露实现。
- **DefaultRedisClient**：唯一实现，基于 re.this，负责连接池、序列化（String/Number/Boolean 直接，其它 `toString()`）。
- **RedisComponent**：Neton 组件，`redis { }` DSL，从 `config/redis.conf` 或 DSL 合并配置，创建 `DefaultRedisClient` 并 `ctx.bind(RedisClient::class)`。
- **RedisExtensions**：`get(key)`、`get<T>(key)`、`remember<T>(key, ttl) { }`，基于 `getValue` + kotlinx.serialization。

详细分层与设计取舍见 [neton-docs/neton-redis-design.md](../../neton-docs/neton-redis-design.md)。

## 依赖

- `neton-core`：组件与配置
- `eu.vendeli:rethis:0.3.9`：Redis 底层
- `kotlinx-coroutines`、`kotlinx-serialization`：协程与 JSON

## 示例项目

- [examples/redis-sample](../../examples/redis-sample)：KV、Hash、List、Pipeline、cleanup 等演示。
