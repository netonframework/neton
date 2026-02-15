package neton.database.api

/**
 * Phase 1 实体查询：由 query { } 产出，list/count/page 与 where 同源（SqlBuilder）。
 */
interface EntityQuery<T : Any> {
    suspend fun list(): List<T>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<T>
    /** 指定列后变为投影查询，返回 Row */
    fun select(vararg columnNames: String): ProjectionQuery
}

/**
 * Phase 1 投影查询：select(...) 后，rows/page 返回 Row，不泄漏 sqlx4k。
 */
interface ProjectionQuery {
    suspend fun rows(): List<Row>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Row>
}
