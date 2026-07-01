package neton.database.migration

import neton.database.api.DbContext
import neton.database.sql.Dialect
import neton.database.sql.PostgresDialect
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.Table
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql

/**
 * 极简 fake [DbContext] — 仅支持 [MigrationEngine] / [SchemaHistoryRepository] 用到的
 * raw SQL 模式。绝不用于业务逻辑,只用来锁 contract 测试。
 *
 * 支持:
 *   - history 表存在性查询 (3 方言的 `SELECT ... LIMIT 1` 模式)
 *   - `CREATE TABLE IF NOT EXISTS` (标记表存在)
 *   - `SELECT module_id, version, ... FROM <table> ORDER BY module_id ASC, version ASC`
 *   - `INSERT INTO <table> (...) VALUES (...)`
 *   - `transaction { }` (块内同一上下文)
 *
 * 不支持: JOIN / parameter binding / SqlBuilder built / Table DSL。
 */
internal class FakeMigrationDb(
    /** History 表初始内容 (preset)。`null` = history 表本身不存在。 */
    initialRows: List<SchemaHistoryRepository.Row>? = null,
    /** 执行 INSERT 时,模拟失败的钩子。返回 null = 成功;返回 Throwable = 抛出。 */
    private val onScriptStatement: ((String) -> Throwable?)? = null,
    /** Simulates pooled DDL visibility: history CREATE is visible externally only after tx commit. */
    private val historyCreateRequiresCommit: Boolean = false,
) : DbContext {
    override val dialect: Dialect = PostgresDialect

    override val interceptors: List<QueryInterceptor> = emptyList()

    private var historyTableExists: Boolean = initialRows != null
    private var transactionDepth: Int = 0
    private var pendingHistoryCreate: Boolean = false
    private val historyRows: MutableList<SchemaHistoryRepository.Row> =
        (initialRows ?: emptyList()).toMutableList()

    /** 测试可读: 所有 execute() 调用的 SQL,按顺序。 */
    val executedSql: MutableList<String> = mutableListOf()
    val historyInsertTransactionDepths: MutableList<Int> = mutableListOf()

    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
        val s = sql.trim()
        if (s.startsWith("SELECT current_schema()")) {
            return listOf(SingleColRow("table_schema" to "public"))
        }
        // 三方言 history 表存在性探测
        if (s.contains("sqlite_master") || s.contains("pg_tables") ||
            s.contains("information_schema.tables")
        ) {
            return if (historyTableExists) listOf(SingleColRow("table_schema" to "public")) else emptyList()
        }
        // listAll: SELECT module_id, version, ... FROM <table> ORDER BY ...
        if (s.startsWith("SELECT module_id") && s.contains("FROM ")) {
            check(historyTableExists || (transactionDepth > 0 && pendingHistoryCreate)) {
                "relation test_migrations does not exist"
            }
            if (s.contains("WHERE 1 = 0")) return emptyList()
            return historyRows.map { row ->
                MapRow(
                    mapOf(
                        "module_id" to row.moduleId,
                        "version" to row.version,
                        "description" to row.description,
                        "checksum" to row.checksum,
                        "installed_at" to row.installedAt.toString(),
                        "execution_ms" to row.executionMs.toString(),
                        "success" to if (row.success) "true" else "false",
                        "error_message" to row.errorMessage,
                    )
                )
            }
        }
        return emptyList()
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        executedSql += sql
        val s = sql.trim()

        // CREATE TABLE IF NOT EXISTS — 标记 history 表存在
        if (s.startsWith("CREATE TABLE IF NOT EXISTS")) {
            if (historyCreateRequiresCommit) {
                pendingHistoryCreate = true
            } else {
                historyTableExists = true
            }
            return 0L
        }

        // INSERT INTO <history> — 解析 VALUES 然后 append
        if (s.startsWith("INSERT INTO ") && s.contains("VALUES")) {
            historyInsertTransactionDepths += transactionDepth
            historyRows += parseInsert(s)
            return 1L
        }

        // 其它 SQL (脚本里的 CREATE TABLE / INSERT 等业务 DDL) — onScriptStatement 钩子
        // 可让测试模拟"第 N 条语句失败"
        onScriptStatement?.invoke(s)?.let { throw it }
        return 0L
    }

    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> =
        error("FakeMigrationDb does not support DSL queries")

    override suspend fun query(built: BuiltSql): List<Row> =
        error("FakeMigrationDb does not support DSL queries")

    override suspend fun executeBuilt(built: BuiltSql): Long =
        error("FakeMigrationDb does not support DSL queries")

    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R {
        transactionDepth++
        return try {
            block()
        } finally {
            transactionDepth--
            if (transactionDepth == 0 && pendingHistoryCreate) {
                historyTableExists = true
                pendingHistoryCreate = false
            }
        }
    }

    /**
     * 解析 INSERT VALUES('a', 'b', 'c', 'd', 12, 34, TRUE/1, NULL/'err') 反查回 Row.
     * 只支持 SchemaHistoryRepository.insert 生成的固定形态。
     */
    private fun parseInsert(sql: String): SchemaHistoryRepository.Row {
        val valuesPart = sql.substringAfter("VALUES").trim()
        val inner = valuesPart.removePrefix("(").removeSuffix(")").trim().trimEnd(')')
        val tokens = splitCsv(inner)
        require(tokens.size == 8) { "fake parser expects 8 INSERT tokens, got ${tokens.size}: $tokens" }
        return SchemaHistoryRepository.Row(
            moduleId = stripQuotes(tokens[0]),
            version = stripQuotes(tokens[1]),
            description = stripQuotes(tokens[2]),
            checksum = stripQuotes(tokens[3]),
            installedAt = tokens[4].trim().toLong(),
            executionMs = tokens[5].trim().toLong(),
            success = when (tokens[6].trim().uppercase()) {
                "TRUE", "1" -> true
                "FALSE", "0" -> false
                else -> error("unknown bool literal: ${tokens[6]}")
            },
            errorMessage = if (tokens[7].trim() == "NULL") null else stripQuotes(tokens[7]),
        )
    }

    private fun stripQuotes(s: String): String =
        s.trim().removeSurrounding("'").replace("''", "'")

    /** 按顶层逗号分,忽略单引号内的逗号 (因为 history insert 没嵌套结构,够用)。 */
    private fun splitCsv(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' -> {
                    cur.append(c)
                    if (inQuote && i + 1 < s.length && s[i + 1] == '\'') {
                        cur.append(s[i + 1]); i += 2; continue
                    }
                    inQuote = !inQuote
                    i++
                }
                c == ',' && !inQuote -> {
                    out += cur.toString(); cur.clear(); i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }
}

private class SingleColRow(private val pair: Pair<String, String?>) : Row {
    private fun get(name: String): String? = if (name == pair.first) pair.second else null
    override fun long(name: String) = get(name)?.toLong() ?: error("null long: $name")
    override fun int(name: String) = get(name)?.toInt() ?: error("null int: $name")
    override fun string(name: String) = get(name) ?: error("null string: $name")
    override fun double(name: String) = get(name)?.toDouble() ?: error("null double: $name")
    override fun boolean(name: String) = get(name)?.toBooleanStrict() ?: error("null bool: $name")
    override fun bytes(name: String) = get(name)?.encodeToByteArray() ?: error("null bytes: $name")
    override fun longOrNull(name: String) = get(name)?.toLongOrNull()
    override fun intOrNull(name: String) = get(name)?.toIntOrNull()
    override fun stringOrNull(name: String) = get(name)
    override fun doubleOrNull(name: String) = get(name)?.toDoubleOrNull()
    override fun booleanOrNull(name: String) = get(name)?.toBooleanStrictOrNull()
    override fun bytesOrNull(name: String) = get(name)?.encodeToByteArray()
}

private class MapRow(private val map: Map<String, String?>) : Row {
    private fun get(name: String): String? = map[name]
    override fun long(name: String) = get(name)?.toLong() ?: error("null long: $name")
    override fun int(name: String) = get(name)?.toInt() ?: error("null int: $name")
    override fun string(name: String) = get(name) ?: error("null string: $name")
    override fun double(name: String) = get(name)?.toDouble() ?: error("null double: $name")
    override fun boolean(name: String) = get(name)?.toBooleanStrict() ?: error("null bool: $name")
    override fun bytes(name: String) = get(name)?.encodeToByteArray() ?: error("null bytes: $name")
    override fun longOrNull(name: String) = get(name)?.toLongOrNull()
    override fun intOrNull(name: String) = get(name)?.toIntOrNull()
    override fun stringOrNull(name: String) = get(name)
    override fun doubleOrNull(name: String) = get(name)?.toDoubleOrNull()
    override fun booleanOrNull(name: String) = get(name)?.toBooleanStrictOrNull()
    override fun bytesOrNull(name: String) = get(name)?.encodeToByteArray()
}
