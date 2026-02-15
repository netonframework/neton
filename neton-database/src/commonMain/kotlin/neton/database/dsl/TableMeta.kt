package neton.database.dsl

/**
 * 表元数据，用于 QueryAst。
 * 可由 EntityMeta.table 构造：TableMeta(entityMeta.table)
 */
data class TableMeta(val tableName: String)
