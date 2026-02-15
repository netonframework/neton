package neton.database.dsl

/**
 * Phase 1 查询元数据：表信息，供 QueryScope 构建 QueryAst。
 * 可从 api.EntityMeta 适配：QueryMeta(TableMeta(entityMeta.table))
 */
data class QueryMeta<T : Any>(val tableMeta: TableMeta)
