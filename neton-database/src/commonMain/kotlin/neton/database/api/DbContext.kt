package neton.database.api

import neton.database.dsl.SelectAst
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql

/**
 * 数据库执行上下文：NetonSQL 的统一执行门面（execution gateway）。
 *
 * C+ 冻结职责：
 * - Phase 1（单表 CRUD / QueryAst）
 * - Phase 4（JOIN / SelectAst）
 *
 * 所有 SQL 执行必须通过 DbContext 进行。
 *
 * 冻结原则：执行统一，API 稳定。
 * 外部 API（如 SystemUserTable.query {}）保持不变，
 * 但内部必须走 DbContext 执行链。
 */
interface DbContext {
    /**
     * Phase 1 保留：raw SQL 逃生口（fetchAll）。
     */
    suspend fun fetchAll(sql: String, params: Map<String, Any?> = emptyMap()): List<Row>

    /**
     * Phase 1 保留：raw SQL 逃生口（execute）。
     */
    suspend fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Long

    /**
     * Phase 4 JOIN 查询入口（替代原顶层 from 函数，绑定执行上下文）。
     */
    fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>>

    /**
     * C+ 统一执行入口：查询（Phase 1 + Phase 4）。
     * 内部调用拦截链：beforeXxx → SqlBuilder → driver → onExecute/onError。
     *
     * @param built SqlBuilder 生成的 SQL + 参数
     * @return Row 列表
     */
    suspend fun query(built: BuiltSql): List<Row>

    /**
     * C+ 统一执行入口：更新（INSERT / UPDATE / DELETE）。
     *
     * @param built SqlBuilder 生成的 SQL + 参数
     * @return 影响行数
     */
    suspend fun executeBuilt(built: BuiltSql): Long

    /**
     * 事务边界（C+ 统一事务控制）。
     *
     * @param block 事务体
     * @return 事务结果
     */
    suspend fun <R> transaction(block: suspend DbContext.() -> R): R

    /**
     * Interceptor 链（只读，C+ 扩展点）。
     * 用于多租户、数据权限、慢 SQL 统计、Metrics 埋点。
     */
    val interceptors: List<QueryInterceptor>
}
