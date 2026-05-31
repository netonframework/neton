package neton.database.migration

/**
 * 把多语句 SQL 脚本切成单语句列表,让 engine 能逐条执行。
 *
 * 解决 sqlx4k PG `execute()` 对 multi-statement 静默吞: 只跑第一条但返回 OK
 * (历史上 game module V007/V008 "OK 但 schema 没变 + history 没写" bug 由此而来)。
 *
 * 解析规则:
 *  - `--` 行注释直到行尾
 *  - `/* ... */` 块注释(不嵌套,与 ANSI SQL 一致;不支持 PG 嵌套块注释)
 *  - `'...'` 单引号字符串字面量;`''` 是 escape
 *  - `"..."` 双引号标识符;`""` 是 escape
 *  - `$$...$$` 或 `$tag$...$tag$` PG dollar-quoted block (块内 raw)
 *  - `;` 在以上区域之外是 statement 边界
 *
 * 返回:
 *  - 列表中每个元素是 trim 过的单语句(不含尾部 `;`)
 *  - 跳过纯空白/纯注释的语句
 *
 * 不支持:
 *  - PG 嵌套块注释
 *  - 反引号(MySQL 标识符) - 当成普通字符
 */
internal object MigrationSqlSplitter {

    fun split(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        val n = sql.length
        var i = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var dollarTag: String? = null

        while (i < n) {
            val ch = sql[i]
            val next = if (i + 1 < n) sql[i + 1] else ' '

            when {
                inLineComment -> {
                    current.append(ch)
                    if (ch == '\n') inLineComment = false
                    i++
                }
                inBlockComment -> {
                    current.append(ch)
                    if (ch == '*' && next == '/') {
                        current.append(next)
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                }
                dollarTag != null -> {
                    // Kotlin string-template parsing of `"$$..."` is fragile (consecutive `$`
                    // become literal). 显式拼接以保证 `$tag$` / `$$` 正确。
                    val closing = "\$$dollarTag\$"
                    if (sql.regionMatches(i, closing, 0, closing.length)) {
                        current.append(closing)
                        i += closing.length
                        dollarTag = null
                    } else {
                        current.append(ch)
                        i++
                    }
                }
                inSingleQuote -> {
                    current.append(ch)
                    if (ch == '\'') {
                        if (next == '\'') {
                            current.append(next)
                            i += 2
                            continue
                        }
                        inSingleQuote = false
                    }
                    i++
                }
                inDoubleQuote -> {
                    current.append(ch)
                    if (ch == '"') {
                        if (next == '"') {
                            current.append(next)
                            i += 2
                            continue
                        }
                        inDoubleQuote = false
                    }
                    i++
                }
                ch == '-' && next == '-' -> {
                    inLineComment = true
                    current.append(ch); current.append(next)
                    i += 2
                }
                ch == '/' && next == '*' -> {
                    inBlockComment = true
                    current.append(ch); current.append(next)
                    i += 2
                }
                ch == '\'' -> {
                    inSingleQuote = true
                    current.append(ch)
                    i++
                }
                ch == '"' -> {
                    inDoubleQuote = true
                    current.append(ch)
                    i++
                }
                ch == '$' -> {
                    val tag = matchDollarTag(sql, i)
                    if (tag != null) {
                        current.append(tag)
                        dollarTag = tag.substring(1, tag.length - 1)
                        i += tag.length
                    } else {
                        current.append(ch)
                        i++
                    }
                }
                ch == ';' -> {
                    val stmt = current.toString().trim()
                    if (!isBlankOrCommentOnly(stmt)) {
                        statements.add(stmt)
                    }
                    current.clear()
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        val tail = current.toString().trim()
        if (!isBlankOrCommentOnly(tail)) {
            statements.add(tail)
        }
        return statements
    }

    private fun matchDollarTag(sql: String, start: Int): String? {
        if (start >= sql.length || sql[start] != '$') return null
        if (start + 1 < sql.length && sql[start + 1] == '$') return "$$"
        var end = start + 1
        if (end >= sql.length) return null
        val first = sql[end]
        if (!(first.isLetter() || first == '_')) return null
        end++
        while (end < sql.length) {
            val c = sql[end]
            if (c == '$') return sql.substring(start, end + 1)
            if (!(c.isLetterOrDigit() || c == '_')) return null
            end++
        }
        return null
    }

    private fun isBlankOrCommentOnly(stmt: String): Boolean {
        if (stmt.isEmpty()) return true
        var i = 0
        val n = stmt.length
        var inLine = false
        var inBlock = false
        while (i < n) {
            val c = stmt[i]
            val next = if (i + 1 < n) stmt[i + 1] else ' '
            when {
                inLine -> {
                    if (c == '\n') inLine = false
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i += 2
                    } else {
                        i++
                    }
                }
                c.isWhitespace() -> i++
                c == '-' && next == '-' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                else -> return false
            }
        }
        return true
    }
}
