package neton.database.dsl

import kotlin.reflect.KProperty1

/**
 * 查询中的表引用：持有 TableDef + 自动分配的 alias。
 * 用户通过 from(Table) / join(Table) 获得 TableRef，
 * 然后通过 tableRef[Entity::prop] 获得带表归属的 ColRef。
 *
 * alias 由 SelectBuilder 自动分配（t1, t2, t3...），用户不手写。
 */
class TableRef<T : Any> internal constructor(
    internal val def: TableDef<T>,
    internal val alias: String        // 自动分配：t1, t2, t3...
) {
    /** 列引用：tableRef.username（KSP 扩展属性）或 tableRef[SystemUser::username] → ColRef<T, V> */
    operator fun <V> get(prop: KProperty1<T, V>): ColRef<T, V> =
        ColRef(this, def.resolve(prop))
}

/**
 * 查询中的列引用：TableRef + Column。
 * 自带表归属（通过 alias），跨表不冲突。
 *
 * 注意：ColRef 不提供任何 SQL 字符串方法。
 * SQL 输出完全由 SqlBuilder 负责。
 */
class ColRef<T : Any, V> internal constructor(
    internal val tableRef: TableRef<T>,
    internal val column: Column<T, V>
) {
    internal val alias: String get() = tableRef.alias
    internal val columnName: String get() = column.columnName
}
