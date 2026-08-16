import kotlin.time.Duration.Companion.minutes
import neton.cache.cache
import neton.core.Neton
import neton.http.http
import neton.redis.redis
import neton.routing.routing

/**
 * `@Cacheable` / `@CachePut` / `@CacheEvict` 的最小可运行示例。
 *
 * 启动顺序有硬约束：**cache 的 L2 是 neton-redis，必须先装 `redis { }`**，
 * 否则 `cache { }` 会在启动时直接失败（不静默降级成只有 L1）。
 *
 * 运行前需要一个本地 Redis：
 * ```
 * docker run -d --rm -p 6379:6379 redis:7-alpine
 * ./gradlew :examples:cache-demo:runDebugExecutableMacosArm64
 * ```
 *
 * 试：
 * ```
 * curl localhost:8083/api/products/1     # 首次穿透到 loader（日志有 product.load）
 * curl localhost:8083/api/products/1     # 第二次命中缓存，无 product.load
 * curl -X POST localhost:8083/api/products/1/rename?name=Foo   # @CachePut 覆盖缓存
 * curl -X DELETE localhost:8083/api/products/1                 # @CacheEvict 清除
 * ```
 */
fun main(args: Array<String>) {
    Neton.run(args) {
        http {
            port = 8083
        }

        // cache 的 L2 依赖 redis，必须先装
        redis { }

        cache {
            // 声明命名缓存；@Cacheable(name = "product") 里的 name 必须在这里（或 cache.conf）声明过
            cache("product") {
                ttl = 10.minutes
                maxSize = 1_000
            }
        }

        routing { }

        // KSP 为本应用的 @Controller 生成的注册器，必须显式传入

        modules(neton.core.generated.GeneratedInitializer)
    }
}
