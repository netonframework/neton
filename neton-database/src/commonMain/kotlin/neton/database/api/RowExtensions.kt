package neton.database.api

import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableRef
import neton.database.dsl.TableDefRegistry
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Row → Entity 映射扩展（Phase 3）
 */

inline fun <reified T : Any> Row.into(): T =
    EntityMapperRegistry.get(T::class).map(this)

inline fun <reified T : Any> Row.into(prefix: String): T =
    EntityMapperRegistry.get(T::class).map(PrefixedRow(this, prefix))

/**
 * LEFT JOIN 友好的映射：如果关联表主键列为 null，认为 LEFT JOIN 未命中，返回 null。
 * 不使用 try/catch（避免吞掉真实 bug）。
 *
 * pk 参数为关联表的主键属性（KProperty1），框架通过 TableDef.resolve() 得到真实列名。
 * 这样不硬编码 "id"，适配任何主键名。
 *
 * 用法：
 *   row.intoOrNull<Role>("role_", Role::id)
 *   row.intoOrNull<Category>("cat_", Category::code)   // 主键不是 id 也 OK
 */
// 移除 inline 以允许调用 internal TableDefRegistry
fun <T : Any, ID> Row.intoOrNull(prefix: String, pk: KProperty1<T, ID>, klass: KClass<T>): T? {
    val pkColumn = TableDefRegistry.resolve(klass, pk).columnName
    // 检测主键列：prefix + pkColumn 为 null 则认为 LEFT JOIN 未命中
    if (stringOrNull(prefix + pkColumn) == null) return null
    return EntityMapperRegistry.get<T>(klass).map(PrefixedRow(this, prefix))
}

// reified 版本（便捷调用）
inline fun <reified T : Any, ID> Row.intoOrNull(prefix: String, pk: KProperty1<T, ID>): T? =
    intoOrNull(prefix, pk, T::class)

/**
 * 从 JOIN 结果行中按 TableRef + KProperty1 强类型取值。
 * 内部根据 TableRef.alias + TableDef.resolve(prop).columnName 计算实际列名。
 *
 * 用于聚合 helper 的 key 等场景，避免写字符串列名：
 *   key = { row -> row.get(U, SystemUser::id) }   // 而不是 row.long("id")
 */
fun <T : Any, V> Row.get(ref: TableRef<T>, prop: KProperty1<T, V>): V {
    val col = ref.def.resolve(prop)
    val qualifiedName = "${ref.alias}_${col.columnName}"  // SELECT 输出的列别名
    // 根据属性类型分发（实现层根据 Column 元数据判断类型）
    return readQualified(qualifiedName, col)
}

fun <T : Any, V> Row.getOrNull(ref: TableRef<T>, prop: KProperty1<T, V>): V? {
    val col = ref.def.resolve(prop)
    val qualifiedName = "${ref.alias}_${col.columnName}"
    return readQualifiedOrNull(qualifiedName, col)
}

/**
 * 按列元数据从 Row 读取值，供 TypedProjectedSelectN 的 read 函数使用。
 * 运行时 dispatch 基于 ColumnType，不依赖 JVM 反射（KMP Native 安全）。
 *
 * qualifiedName = "{alias}_{columnName}"（原则 13 的别名规则）。
 */
@Suppress("UNCHECKED_CAST")
internal fun <V> Row.readQualified(qualifiedName: String, column: Column<*, V>): V =
    if (column.nullable) readQualifiedOrNull(qualifiedName, column) as V
    else when (column.type) {
        ColumnType.LONG    -> long(qualifiedName)
        ColumnType.INT     -> int(qualifiedName)
        ColumnType.STRING  -> string(qualifiedName)
        ColumnType.DOUBLE  -> double(qualifiedName)
        ColumnType.BOOLEAN -> boolean(qualifiedName)
        ColumnType.BYTES   -> bytes(qualifiedName)
    } as V

@Suppress("UNCHECKED_CAST")
internal fun <V> Row.readQualifiedOrNull(qualifiedName: String, column: Column<*, V>): V? =
    when (column.type) {
        ColumnType.LONG    -> longOrNull(qualifiedName)
        ColumnType.INT     -> intOrNull(qualifiedName)
        ColumnType.STRING  -> stringOrNull(qualifiedName)
        ColumnType.DOUBLE  -> doubleOrNull(qualifiedName)
        ColumnType.BOOLEAN -> booleanOrNull(qualifiedName)
        ColumnType.BYTES   -> bytesOrNull(qualifiedName)
    } as V?
