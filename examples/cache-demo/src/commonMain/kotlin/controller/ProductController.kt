package controller

import kotlinx.serialization.Serializable
import neton.cache.CacheEvict
import neton.cache.CachePut
import neton.cache.Cacheable
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Query
import neton.logging.Log
import neton.logging.Logger

@Serializable
data class Product(val id: Long, val name: String)

/**
 * 注解式缓存的三个动作。织入由 KSP 在编译期生成，方法体本身不写任何缓存代码。
 *
 * 注意：KSP 只对 `@Controller` 类的方法织入缓存注解；标在 Logic/Service 上会编译期报错，
 * 而不是静默失效。key 也只能引用 path/query 参数，body 参与不了。
 */
@Controller("/api/products")
@Log
class ProductController(
    private val log: Logger,
) {

    /** 读缓存 → 未命中则执行方法体并回填。ttlMs 不写则用 cache("product") 声明的 ttl。 */
    @Get("/{id}")
    @Cacheable(name = "product", key = "{id}")
    suspend fun get(id: Long): Product {
        // 命中缓存时这行日志不会出现——这就是验证缓存是否生效的方式
        log.info("product.load", mapOf("id" to id))
        return Product(id = id, name = "product-$id")
    }

    /** 写库后用返回值覆盖缓存。 */
    @Post("/{id}/rename")
    @CachePut(name = "product", key = "{id}")
    suspend fun rename(id: Long, @Query name: String): Product {
        log.info("product.rename", mapOf("id" to id, "name" to name))
        return Product(id = id, name = name)
    }

    /** 删除后清除缓存条目。 */
    @Delete("/{id}")
    @CacheEvict(name = "product", key = "{id}")
    suspend fun delete(id: Long) {
        log.info("product.delete", mapOf("id" to id))
    }
}
