package neton.database.api

/**
 * 单行查询结果。
 * 列名规则：按 SQL 输出列名原样匹配，大小写统一 lowercase（驱动层负责转换）。
 * JOIN 场景列名为 {alias}_{columnName}（原则 13），如 t1_id / t2_user_id。
 *
 * 存在性检测：通过 stringOrNull(name) == null 判断列是否为 null（intoOrNull 依赖此行为）。
 * 不引入 hasColumn() 方法，避免驱动层 API 依赖。
 */
interface Row {
    // 非 null 读取（列不存在或值为 null 时抛异常）
    fun long(name: String): Long
    fun int(name: String): Int
    fun string(name: String): String
    fun double(name: String): Double
    fun boolean(name: String): Boolean
    fun bytes(name: String): ByteArray

    // 可 null 读取（列不存在或值为 null 时返回 null）
    fun longOrNull(name: String): Long?
    fun intOrNull(name: String): Int?
    fun stringOrNull(name: String): String?
    fun doubleOrNull(name: String): Double?
    fun booleanOrNull(name: String): Boolean?
    fun bytesOrNull(name: String): ByteArray?
}
