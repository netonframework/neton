package neton.cache

/**
 * 缓存注解 v1：与 [Neton-Cache-Annotation-Spec-v1] 一致，仅此三个。
 * KSP 织入见 neton-ksp ControllerProcessor；CacheManager 通过 context.getApplicationContext()!!.get(CacheManager::class) 获取。
 */

/** 读缓存 + 回源 + 回填（getOrPut 声明式）。命中直接返回，miss 执行方法并回填；支持 singleflight。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Cacheable(
    val name: String,
    val key: String = "",
    val ttlMs: Long = 0,
)

/** 方法成功返回后 put；失败不 put。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class CachePut(
    val name: String,
    val key: String = "",
    val ttlMs: Long = 0,
)

/** 方法成功返回后 delete（或 allEntries=true 时 clear）；失败不删。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class CacheEvict(
    val name: String,
    val key: String = "",
    val allEntries: Boolean = false,
)
