package neton.database.api

/**
 * Row 包装器：自动剥离列名前缀。
 * SQL: SELECT r.id AS role_id, r.name AS role_name
 * 用法: row.into<Role>("role_")  → PrefixedRow("role_id") → delegate 找 "role_id"
 */
class PrefixedRow(private val delegate: Row, private val prefix: String) : Row {
    override fun long(name: String): Long = delegate.long(prefix + name)
    override fun longOrNull(name: String): Long? = delegate.longOrNull(prefix + name)
    override fun string(name: String): String = delegate.string(prefix + name)
    override fun stringOrNull(name: String): String? = delegate.stringOrNull(prefix + name)
    override fun int(name: String): Int = delegate.int(prefix + name)
    override fun intOrNull(name: String): Int? = delegate.intOrNull(prefix + name)
    override fun double(name: String): Double = delegate.double(prefix + name)
    override fun doubleOrNull(name: String): Double? = delegate.doubleOrNull(prefix + name)
    override fun boolean(name: String): Boolean = delegate.boolean(prefix + name)
    override fun booleanOrNull(name: String): Boolean? = delegate.booleanOrNull(prefix + name)
    override fun bytes(name: String): ByteArray = delegate.bytes(prefix + name)
    override fun bytesOrNull(name: String): ByteArray? = delegate.bytesOrNull(prefix + name)
}
