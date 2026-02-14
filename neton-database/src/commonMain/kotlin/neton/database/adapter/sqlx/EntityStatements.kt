package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.Statement

/**
 * 实体 CRUD Statement 静态持有接口
 * 由 KSP 生成或手写，使用 sqlx4k Statement
 */
interface EntityStatements {
    val selectById: Statement
    val selectAll: Statement
    val countAll: Statement
    val insert: Statement
    val update: Statement
    val deleteById: Statement
}
