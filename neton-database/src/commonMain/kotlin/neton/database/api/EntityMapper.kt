package neton.database.api

import kotlin.reflect.KClass

/**
 * Neton 自有的 Row → Entity 映射器。
 * 基于 neton.database.api.Row 接口，与 sqlx4k RowMapper 独立。
 * KSP 为每个 @Table 实体生成。
 */
fun interface EntityMapper<T : Any> {
    fun map(row: Row): T
}

/**
 * EntityMapper 注册表。
 * 注册只发生在 DatabaseComponent.init() 内（统一入口），不允许散落注册。
 */
object EntityMapperRegistry {
    private val mappers = mutableMapOf<KClass<*>, EntityMapper<*>>()

    fun <T : Any> register(klass: KClass<T>, mapper: EntityMapper<T>) {
        mappers[klass] = mapper
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(klass: KClass<T>): EntityMapper<T> =
        mappers[klass] as? EntityMapper<T>
            ?: throw IllegalStateException("No EntityMapper for ${klass.simpleName}. Ensure @Table is annotated and DatabaseComponent is initialized.")
}
