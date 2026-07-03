package neton.database.api

import neton.database.dsl.*

/**
 * Phase 1 实体查询：由 query { } 产出，list/count/page 与 where 同源（SqlBuilder）。
 */
interface EntityQuery<T : Any> {
    suspend fun list(): List<T>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<T>

    /** 按 where 条件批量删除，返回受影响行数。 */
    suspend fun delete(): Long

    /**
     * 按 where 条件批量更新，返回受影响行数。
     * 用于乐观锁等"WHERE 条件 + 部分列 SET"的场景。
     *
     * 示例：
     * ```
     * UserTable.query {
     *     where { and(User::id eq id, User::status eq oldStatus) }
     * }.update {
     *     set(User::status, newStatus)
     * }
     * ```
     */
    suspend fun update(block: UpdateScope<T>.() -> Unit): Long

    /** 指定列后变为投影查询，返回 Row */
    fun select(vararg columnNames: String): ProjectionQuery

    // Typed projection via KProperty1 (`select(User::id, User::name)`) is deferred to 1.1.
    // The 1.0 surface exposes only string-column projection above; a typed projection DSL
    // will be designed separately (see NETON-1.0-STANDARDIZATION-RC / STD-2).
}

/**
 * Phase 1 投影查询：select(...) 后，rows/page 返回 Row，不泄漏 sqlx4k。
 */
interface ProjectionQuery {
    suspend fun rows(): List<Row>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Row>
}
