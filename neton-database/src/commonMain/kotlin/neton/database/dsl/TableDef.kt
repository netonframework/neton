package neton.database.dsl

import kotlin.reflect.KProperty1

/**
 * 表定义：持有表名和 KProperty → Column 的映射。
 * KSP 为每个 @Table 实体生成一个实现。
 *
 * 与 Phase 1 的 Table<T, ID>（CRUD 接口）是不同的概念：
 * - TableDef：表的「元数据 + 列映射」，DSL 内部使用
 * - Table<T, ID>：表的「CRUD 操作」，用户直接使用
 */
interface TableDef<T : Any> {
    val tableName: String
    val columns: List<Column<T, *>>

    /** KProperty name → Column 的查找。KSP 生成的实现用 Map 做 O(1) 查找。 */
    fun <V> resolve(prop: KProperty1<T, V>): Column<T, V>
}
