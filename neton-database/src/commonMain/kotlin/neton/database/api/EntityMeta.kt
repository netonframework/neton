package neton.database.api

/**
 * 实体元数据 - 仅表名与列信息，由 KSP 生成
 * 不包含行为，业务层不直接使用
 */
interface EntityMeta<T : Any> {
    val table: String
    val idColumn: String
    val columns: List<String>
}
