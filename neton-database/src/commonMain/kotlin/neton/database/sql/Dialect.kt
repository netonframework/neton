package neton.database.sql

/**
 * SQL 方言：占位符、标识符引用、LIMIT/OFFSET、LIKE 表达式。
 * PG: $1, $2；MySQL: ?；MySQL LIMIT 为 LIMIT offset, limit。
 */
interface Dialect {
    val name: String
    fun placeholder(index: Int): String
    fun quoteIdent(name: String): String
    fun limitOffset(limitPlaceholder: String?, offsetPlaceholder: String?): String
    fun likeExpression(columnSql: String, valuePlaceholder: String): String
}

object PostgresDialect : Dialect {
    override val name = "postgres"
    override fun placeholder(index: Int) = "$$index"
    override fun quoteIdent(name: String) = "\"$name\""
    override fun limitOffset(limitPlaceholder: String?, offsetPlaceholder: String?) = when {
        limitPlaceholder != null && offsetPlaceholder != null ->
            "LIMIT $limitPlaceholder OFFSET $offsetPlaceholder"
        limitPlaceholder != null -> "LIMIT $limitPlaceholder"
        else -> ""
    }
    override fun likeExpression(columnSql: String, valuePlaceholder: String) =
        "$columnSql LIKE $valuePlaceholder"
}

object MySqlDialect : Dialect {
    override val name = "mysql"
    override fun placeholder(index: Int) = "?"
    override fun quoteIdent(name: String) = "`$name`"
    override fun limitOffset(limitPlaceholder: String?, offsetPlaceholder: String?) = when {
        limitPlaceholder != null && offsetPlaceholder != null ->
            "LIMIT $offsetPlaceholder, $limitPlaceholder"
        limitPlaceholder != null -> "LIMIT $limitPlaceholder"
        else -> ""
    }
    override fun likeExpression(columnSql: String, valuePlaceholder: String) =
        "$columnSql LIKE $valuePlaceholder"
}

/** SQLite：占位符 ?，LIMIT/OFFSET 同 PostgreSQL */
object SqliteDialect : Dialect {
    override val name = "sqlite"
    override fun placeholder(index: Int) = "?"
    override fun quoteIdent(name: String) = "\"$name\""
    override fun limitOffset(limitPlaceholder: String?, offsetPlaceholder: String?) = when {
        limitPlaceholder != null && offsetPlaceholder != null ->
            "LIMIT $limitPlaceholder OFFSET $offsetPlaceholder"
        limitPlaceholder != null -> "LIMIT $limitPlaceholder"
        else -> ""
    }
    override fun likeExpression(columnSql: String, valuePlaceholder: String) =
        "$columnSql LIKE $valuePlaceholder"
}
