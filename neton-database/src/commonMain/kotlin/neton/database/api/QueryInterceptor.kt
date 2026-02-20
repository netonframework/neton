package neton.database.api

import neton.database.dsl.QueryAst
import neton.database.dsl.SelectAst

/**
 * NetonSQL 的唯一 AST 改写与执行观测扩展点（C+ 冻结接口）。
 *
 * 用于：
 * - 多租户注入
 * - 数据权限注入
 * - 软删除自动注入
 * - SQL 执行日志
 * - 慢 SQL 统计
 * - Metrics 埋点
 *
 * 设计原则：
 * Interceptor 只负责 AST 改写和执行观测，不参与业务逻辑。
 *
 * ⚠️ 明确排除：
 * - ❌ 不允许 afterFetch(List<T>) 这种结果改写钩子
 * - ❌ 不允许在拦截器中修改返回数据
 * - ❌ 不允许在拦截器中执行额外 SQL
 */
interface QueryInterceptor {

    /**
     * Phase 1 单表查询改写入口。
     * 可用于注入 WHERE 条件（如多租户、软删除、数据权限）。
     *
     * @param ast 原始查询 AST
     * @return 改写后的 AST（如不需要改写则返回原 ast）
     */
    fun beforeQuery(ast: QueryAst<*>): QueryAst<*> = ast

    /**
     * Phase 4 JOIN 查询改写入口。
     * 可用于注入 WHERE 条件（如多租户、软删除、数据权限）。
     *
     * @param ast 原始 JOIN AST
     * @return 改写后的 AST（如不需要改写则返回原 ast）
     */
    fun beforeSelect(ast: SelectAst): SelectAst = ast

    /**
     * 执行成功后观测（只读，不可修改结果）。
     * 可用于：
     * - SQL 执行日志
     * - 慢 SQL 告警
     * - Metrics 埋点
     *
     * @param sql 实际执行的 SQL 语句
     * @param args SQL 参数列表
     * @param elapsedMs 执行耗时（毫秒）
     */
    fun onExecute(sql: String, args: List<Any?>, elapsedMs: Long) {}

    /**
     * 执行异常观测。
     * 可用于：
     * - 错误日志
     * - 异常 Metrics
     * - 告警通知
     *
     * @param sql 实际执行的 SQL 语句
     * @param args SQL 参数列表
     * @param error 执行异常
     */
    fun onError(sql: String, args: List<Any?>, error: Throwable) {}
}
