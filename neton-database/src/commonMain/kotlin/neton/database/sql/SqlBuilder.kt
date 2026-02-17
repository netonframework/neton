package neton.database.sql

import neton.database.dsl.ColumnRef
import neton.database.dsl.Predicate
import neton.database.dsl.QueryAst

/**
 * 根据 Dialect 将 QueryAst 转为可执行 SQL。
 * Day 1：where / orderBy / limitOffset / projection；IN 空集 → 1=0；LIKE 转义 % / _。
 * Day 2：build 前对 AST 做 normalizeForSoftDelete（由调用方或本类扩展）。
 */
class SqlBuilder(private val dialect: Dialect) {
    private val args = mutableListOf<Any?>()
    private var paramIndex = 1

    private fun nextPlaceholder(): String {
        val p = dialect.placeholder(paramIndex)
        paramIndex += 1
        return p
    }

    private fun addArg(value: Any?): String {
        args += value
        return nextPlaceholder()
    }

    private fun reset() {
        args.clear()
        paramIndex = 1
    }

    fun <T : Any> buildSelect(ast: QueryAst<T>): BuiltSql {
        reset()
        val tableSql = dialect.quoteIdent(ast.table.tableName)
        val selectClause = if (ast.projection.isEmpty()) "SELECT *"
        else "SELECT " + ast.projection.joinToString(", ") { dialect.quoteIdent(it.name) }
        val whereClause = buildWhereClause(ast.where)
        val orderClause = if (ast.orderBy.isEmpty()) ""
        else "ORDER BY " + ast.orderBy.joinToString(", ") { o ->
            "${dialect.quoteIdent(o.column.name)} ${o.dir.name}"
        }
        val limitClause = if (ast.limit != null) {
            val lp = addArg(ast.limit)
            val op = if (ast.offset != null) addArg(ast.offset) else null
            dialect.limitOffset(lp, op)
        } else ""
        val sql = listOf(selectClause, "FROM $tableSql", whereClause, orderClause, limitClause)
            .filter { it.isNotBlank() }.joinToString(" ")
        return BuiltSql(sql, args.toList())
    }

    fun <T : Any> buildCount(ast: QueryAst<T>): BuiltSql {
        reset()
        val tableSql = dialect.quoteIdent(ast.table.tableName)
        val whereClause = buildWhereClause(ast.where)
        val sql = listOf("SELECT COUNT(*)", "FROM $tableSql", whereClause)
            .filter { it.isNotBlank() }.joinToString(" ")
        return BuiltSql(sql, args.toList())
    }

    private fun buildWhereClause(predicate: Predicate?): String {
        if (predicate == null) return ""
        if (predicate is Predicate.True) return ""
        val sql = buildPredicate(predicate)
        return if (sql.isBlank()) "" else "WHERE $sql"
    }

    private fun buildPredicate(p: Predicate): String = when (p) {
        is Predicate.True -> ""
        is Predicate.And -> {
            val parts = p.children.map(::buildPredicate).filter { it.isNotBlank() }
            if (parts.isEmpty()) "" else parts.joinToString(" AND ", "(", ")")
        }

        is Predicate.Or -> {
            val parts = p.children.map(::buildPredicate).filter { it.isNotBlank() }
            if (parts.isEmpty()) "" else parts.joinToString(" OR ", "(", ")")
        }

        is Predicate.Eq -> "${dialect.quoteIdent(p.column.name)} = ${addArg(p.value)}"
        is Predicate.Gt -> cmp(p.column, ">", p.value)
        is Predicate.Ge -> cmp(p.column, ">=", p.value)
        is Predicate.Lt -> cmp(p.column, "<", p.value)
        is Predicate.Le -> cmp(p.column, "<=", p.value)
        is Predicate.Like -> {
            val col = dialect.quoteIdent(p.column.name)
            val ph = addArg(p.value)
            dialect.likeExpression(col, ph)
        }

        is Predicate.In -> {
            if (p.values.isEmpty()) "1 = 0"
            else "${dialect.quoteIdent(p.column.name)} IN (${p.values.map { addArg(it) }.joinToString(", ")})"
        }
    }

    private fun cmp(column: ColumnRef, op: String, value: Any?): String =
        "${dialect.quoteIdent(column.name)} $op ${addArg(value)}"

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
