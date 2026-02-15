package neton.database.sql

/**
 * 将 BuiltSql（位置参数）转为 sqlx4k 可用的「SQL + 命名参数 Map」。
 * PG: $1,$2 → :p1,:p2；MySQL/SQLite: ? → :p1,:p2（按出现顺序）。
 */
fun BuiltSql.toNamedParams(dialect: Dialect): Pair<String, Map<String, Any?>> {
    val params = args.mapIndexed { i, v -> "p${i + 1}" to v }.toMap()
    val newSql = when (dialect.name) {
        "postgres" -> Regex("\\$(\\d+)").replace(this.sql) { ":p${it.groupValues[1]}" }
        else -> {
            var i = 0
            var s = this.sql
            while (s.contains("?")) {
                s = s.replaceFirst("?", ":p${i + 1}")
                i++
            }
            s
        }
    }
    return newSql to params
}
