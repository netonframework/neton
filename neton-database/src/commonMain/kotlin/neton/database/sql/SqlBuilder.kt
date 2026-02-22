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

    // ===== Phase 4: SelectAst → SQL =====

    fun buildSelect(ast: neton.database.dsl.SelectAst): BuiltSql {
        reset()

        // FROM
        val fromSql = "${dialect.quoteIdent(ast.fromTable)} AS ${ast.fromAlias}"

        // JOIN
        val joinsSql = ast.joins.joinToString(" ") { join ->
            val keyword = when (join.type) {
                neton.database.dsl.JoinType.INNER -> "INNER JOIN"
                neton.database.dsl.JoinType.LEFT -> "LEFT JOIN"
                neton.database.dsl.JoinType.RIGHT -> "RIGHT JOIN"
                neton.database.dsl.JoinType.FULL -> "FULL JOIN"
            }
            val target = "${dialect.quoteIdent(join.targetTableName)} AS ${join.targetAlias}"
            val on = "${join.on.leftAlias}.${dialect.quoteIdent(join.on.leftColumn)} = " +
                     "${join.on.rightAlias}.${dialect.quoteIdent(join.on.rightColumn)}"
            "$keyword $target ON $on"
        }

        // SELECT — 所有列必须加 AS {alias}_{column} 别名（原则 13）
        val selectClause = if (ast.projection.isEmpty()) {
            "SELECT *"
        } else {
            "SELECT " + ast.projection.joinToString(", ") { expr ->
                when (expr) {
                    is neton.database.dsl.ProjectionExpr.Col ->
                        "${expr.tableAlias}.${dialect.quoteIdent(expr.columnName)} AS ${expr.tableAlias}_${expr.columnName}"
                    is neton.database.dsl.ProjectionExpr.Agg ->
                        "${expr.expression} AS ${dialect.quoteIdent(expr.outputAlias)}"
                }
            }
        }

        // WHERE
        val whereClause = ast.where?.let { "WHERE ${buildColumnPredicate(it)}" } ?: ""

        // GROUP BY
        val groupByClause = if (ast.groupBy.isEmpty()) "" else {
            "GROUP BY " + ast.groupBy.joinToString(", ") { expr ->
                when (expr) {
                    is neton.database.dsl.ProjectionExpr.Col -> "${expr.tableAlias}.${dialect.quoteIdent(expr.columnName)}"
                    is neton.database.dsl.ProjectionExpr.Agg -> expr.outputAlias  // Phase 5
                }
            }
        }

        // HAVING
        val havingClause = ast.having?.let { "HAVING ${buildColumnPredicate(it)}" } ?: ""

        // ORDER BY
        val orderClause = if (ast.orderBy.isEmpty()) "" else {
            "ORDER BY " + ast.orderBy.joinToString(", ") {
                "${it.tableAlias}.${dialect.quoteIdent(it.column)} ${it.dir.name}"
            }
        }

        // LIMIT/OFFSET
        val limitClause = buildLimitOffset(ast.limit, ast.offset)

        val distinct = if (ast.distinct) "DISTINCT " else ""
        val sql = listOf(
            selectClause.replaceFirst("SELECT ", "SELECT $distinct"),
            "FROM $fromSql", joinsSql,
            whereClause, groupByClause, havingClause, orderClause, limitClause
        ).filter { it.isNotBlank() }.joinToString(" ")

        return BuiltSql(sql, args.toList())
    }

    fun buildCount(ast: neton.database.dsl.SelectAst): BuiltSql {
        reset()
        if (ast.groupBy.isEmpty()) {
            // 无 GROUP BY → 直接 COUNT(*)
            val fromSql = "${dialect.quoteIdent(ast.fromTable)} AS ${ast.fromAlias}"
            val joinsSql = buildJoins(ast)
            val whereClause = ast.where?.let { "WHERE ${buildColumnPredicate(it)}" } ?: ""
            val sql = listOf("SELECT COUNT(*)", "FROM $fromSql", joinsSql, whereClause)
                .filter { it.isNotBlank() }.joinToString(" ")
            return BuiltSql(sql, args.toList())
        } else {
            // 有 GROUP BY → 子查询包裹（原则 14）
            val innerSql = buildSelect(ast.copy(limit = null, offset = null)).sql
            return BuiltSql("SELECT COUNT(*) FROM ($innerSql) AS _count_tmp", args.toList())
        }
    }

    private fun buildJoins(ast: neton.database.dsl.SelectAst): String =
        ast.joins.joinToString(" ") { join ->
            val keyword = when (join.type) {
                neton.database.dsl.JoinType.INNER -> "INNER JOIN"
                neton.database.dsl.JoinType.LEFT -> "LEFT JOIN"
                neton.database.dsl.JoinType.RIGHT -> "RIGHT JOIN"
                neton.database.dsl.JoinType.FULL -> "FULL JOIN"
            }
            val on = "${join.on.leftAlias}.${dialect.quoteIdent(join.on.leftColumn)} = " +
                     "${join.on.rightAlias}.${dialect.quoteIdent(join.on.rightColumn)}"
            "$keyword ${dialect.quoteIdent(join.targetTableName)} AS ${join.targetAlias} ON $on"
        }

    private fun buildLimitOffset(limit: Int?, offset: Int?): String {
        if (limit == null) return ""
        val lp = addArg(limit)
        val op = if (offset != null) addArg(offset) else null
        return dialect.limitOffset(lp, op)
    }

    private fun buildColumnPredicate(p: neton.database.dsl.ColumnPredicate): String = when (p) {
        is neton.database.dsl.ColumnPredicate.Eq -> {
            "${p.tableAlias}.${dialect.quoteIdent(p.column)} = ${addArg(p.value)}"
        }
        is neton.database.dsl.ColumnPredicate.Ne -> {
            "${p.tableAlias}.${dialect.quoteIdent(p.column)} != ${addArg(p.value)}"
        }
        is neton.database.dsl.ColumnPredicate.Like -> {
            dialect.likeExpression(
                "${p.tableAlias}.${dialect.quoteIdent(p.column)}",
                addArg(p.value)
            )
        }
        is neton.database.dsl.ColumnPredicate.In -> {
            if (p.values.isEmpty()) "1 = 0"
            else "${p.tableAlias}.${dialect.quoteIdent(p.column)} IN (${p.values.map { addArg(it) }.joinToString(", ")})"
        }
        is neton.database.dsl.ColumnPredicate.IsNull -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} IS NULL"
        is neton.database.dsl.ColumnPredicate.IsNotNull -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} IS NOT NULL"
        is neton.database.dsl.ColumnPredicate.Between -> {
            val lo = addArg(p.low); val hi = addArg(p.high)
            "${p.tableAlias}.${dialect.quoteIdent(p.column)} BETWEEN $lo AND $hi"
        }
        is neton.database.dsl.ColumnPredicate.Gt -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} > ${addArg(p.value)}"
        is neton.database.dsl.ColumnPredicate.Ge -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} >= ${addArg(p.value)}"
        is neton.database.dsl.ColumnPredicate.Lt -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} < ${addArg(p.value)}"
        is neton.database.dsl.ColumnPredicate.Le -> "${p.tableAlias}.${dialect.quoteIdent(p.column)} <= ${addArg(p.value)}"
        is neton.database.dsl.ColumnPredicate.And -> p.children.joinToString(" AND ") { "(${buildColumnPredicate(it)})" }
        is neton.database.dsl.ColumnPredicate.Or -> p.children.joinToString(" OR ") { "(${buildColumnPredicate(it)})" }
        is neton.database.dsl.ColumnPredicate.True -> "1 = 1"
    }
}
