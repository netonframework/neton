package neton.database.dsl

/**
 * 列的基础类型标记（KSP 生成时确定，readQualified dispatch 用）。
 * 不在此枚举中的复杂类型（JSON、枚举映射等）通过 EntityMapper 手动处理。
 */
enum class ColumnType { LONG, INT, STRING, DOUBLE, BOOLEAN, BYTES }

/**
 * 强类型列引用。
 * T = 所属实体类型，V = 列值类型。
 * KSP 生成，用户不直接使用。
 *
 * 注意：Column 不提供任何 SQL 字符串拼接方法。
 * SQL 输出完全由 SqlBuilder + Dialect 负责。
 */
class Column<T : Any, V>(
    val tableDef: TableDef<T>,
    val columnName: String,        // SQL 列名（snake_case），KSP 编译期确定
    val propertyName: String,      // Kotlin 属性名（camelCase）
    val type: ColumnType,          // ★ Phase 2: 类型标记，供 readQualified dispatch
    val nullable: Boolean          // ★ Phase 2: 对应 V 是否为可 null 类型
)
