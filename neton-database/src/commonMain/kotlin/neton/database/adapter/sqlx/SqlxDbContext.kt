package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.DbContext
import neton.database.api.Table
import neton.database.dsl.*
import neton.database.sql.BuiltSql
import neton.database.sql.SqlBuilder
import kotlin.time.measureTimedValue

/**
 * sqlx4k 适配的 DbContext 实现（C+ 统一执行门面）。
 */
object SqlxDbContext : DbContext {

    // C+: Interceptor 链（只读，未来可通过 register 方法配置）
    override val interceptors: List<QueryInterceptor> = emptyList()

    // ===== Raw SQL 逃生口（Phase 1 兼容）=====

    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
        val db = SqlxDatabase.require()
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        val rows = db.fetchAll(stmt).getOrThrow()
        return rows.map { SqlxRow(it) }
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        val db = SqlxDatabase.require()
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        return db.execute(stmt).getOrThrow()
    }

    // ===== C+ 统一执行入口 =====

    /**
     * C+ 统一查询入口（Phase 1 + Phase 4）。
     * 执行流程：beforeXxx → SqlBuilder → driver → onExecute/onError
     */
    override suspend fun query(built: BuiltSql): List<Row> {
        val (sql, args) = built
        return try {
            val (result, duration) = measureTimedValue {
                // 转换为命名参数（p1, p2, p3...）
                val params = args.mapIndexed { i, v -> "p${i + 1}" to v }.toMap()
                fetchAll(sql, params)
            }
            // 执行成功观测
            interceptors.forEach { it.onExecute(sql, args, duration.inWholeMilliseconds) }
            result
        } catch (e: Throwable) {
            // 执行异常观测
            interceptors.forEach { it.onError(sql, args, e) }
            throw e
        }
    }

    /**
     * C+ 统一更新入口（INSERT / UPDATE / DELETE）。
     */
    override suspend fun executeBuilt(built: BuiltSql): Long {
        val (sql, args) = built
        return try {
            val (result, duration) = measureTimedValue {
                val params = args.mapIndexed { i, v -> "p${i + 1}" to v }.toMap()
                execute(sql, params)
            }
            interceptors.forEach { it.onExecute(sql, args, duration.inWholeMilliseconds) }
            result
        } catch (e: Throwable) {
            interceptors.forEach { it.onError(sql, args, e) }
            throw e
        }
    }

    /**
     * C+ 事务边界（统一事务控制）。
     */
    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R {
        val db = SqlxDatabase.require()
        val transactional = db as? io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional
            ?: throw IllegalStateException("Driver does not support transactions")

        return transactional.transaction {
            TransactionalDbContext(this).block()
        }
    }

    // ===== Phase 4：JOIN 查询入口 =====

    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> {
        val builder = SelectBuilder(this)
        val ref = builder.from(table)
        return builder to ref
    }
}

private class TransactionalDbContext(
    private val tx: Transaction
) : DbContext {
    override val interceptors: List<QueryInterceptor> = SqlxDbContext.interceptors

    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        val rows = tx.fetchAll(stmt).getOrThrow()
        return rows.map { SqlxRow(it) }
    }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        return tx.execute(stmt).getOrThrow()
    }

    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> {
        val builder = SelectBuilder(this)
        val ref = builder.from(table)
        return builder to ref
    }

    override suspend fun query(built: BuiltSql): List<Row> {
        val (sql, args) = built
        return try {
            val (result, duration) = measureTimedValue {
                val params = args.mapIndexed { i, v -> "p${i + 1}" to v }.toMap()
                fetchAll(sql, params)
            }
            interceptors.forEach { it.onExecute(sql, args, duration.inWholeMilliseconds) }
            result
        } catch (e: Throwable) {
            interceptors.forEach { it.onError(sql, args, e) }
            throw e
        }
    }

    override suspend fun executeBuilt(built: BuiltSql): Long {
        val (sql, args) = built
        return try {
            val (result, duration) = measureTimedValue {
                val params = args.mapIndexed { i, v -> "p${i + 1}" to v }.toMap()
                execute(sql, params)
            }
            interceptors.forEach { it.onExecute(sql, args, duration.inWholeMilliseconds) }
            result
        } catch (e: Throwable) {
            interceptors.forEach { it.onError(sql, args, e) }
            throw e
        }
    }

    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = block()
}

// Phase 4：SelectAst 执行（module-internal，C+ 统一路径）
internal suspend fun DbContext.selectRows(ast: SelectAst): List<Row> {
    // AST 改写（interceptor.beforeSelect）
    val rewrittenAst = interceptors.fold(ast) { currentAst, interceptor ->
        interceptor.beforeSelect(currentAst)
    }

    // 构建 SQL
    val driver = SqlxDatabase.currentDriver()
    val dialect = driver.toDialect()
    val built = SqlBuilder(dialect).buildSelect(rewrittenAst)

    // 执行（走 C+ 统一入口，触发 onExecute/onError）
    return query(built)
}

internal suspend fun DbContext.countRows(ast: SelectAst): Long {
    // AST 改写（interceptor.beforeSelect）
    val rewrittenAst = interceptors.fold(ast) { currentAst, interceptor ->
        interceptor.beforeSelect(currentAst)
    }

    // 构建 SQL
    val driver = SqlxDatabase.currentDriver()
    val dialect = driver.toDialect()
    val built = SqlBuilder(dialect).buildCount(rewrittenAst)

    // 执行（走 C+ 统一入口）
    val rows = query(built)
    return rows.firstOrNull()?.long("count") ?: 0L
}

private fun neton.database.config.DatabaseDriver.toDialect(): neton.database.sql.Dialect =
    when (this) {
        neton.database.config.DatabaseDriver.POSTGRESQL -> neton.database.sql.PostgresDialect
        neton.database.config.DatabaseDriver.MYSQL -> neton.database.sql.MySqlDialect
        neton.database.config.DatabaseDriver.SQLITE,
        neton.database.config.DatabaseDriver.MEMORY -> neton.database.sql.SqliteDialect
    }

/**
 * 适配 sqlx4k ResultSet.Row 为 neton.database.api.Row。
 * sqlx4k Column 仅提供 asString/asStringOrNull，数值类型由本层解析。
 */
private class SqlxRow(private val row: io.github.smyrgeorge.sqlx4k.ResultSet.Row) : Row {
    private fun str(name: String): String? = row.get(name).asStringOrNull()

    override fun long(name: String): Long =
        str(name)?.toLongOrNull() ?: throw IllegalArgumentException("null or invalid long: $name")

    override fun longOrNull(name: String): Long? = str(name)?.toLongOrNull()
    override fun string(name: String): String = str(name) ?: throw IllegalArgumentException("null string: $name")
    override fun stringOrNull(name: String): String? = str(name)
    override fun int(name: String): Int =
        str(name)?.toIntOrNull() ?: throw IllegalArgumentException("null or invalid int: $name")

    override fun intOrNull(name: String): Int? = str(name)?.toIntOrNull()
    override fun double(name: String): Double =
        str(name)?.toDoubleOrNull() ?: throw IllegalArgumentException("null or invalid double: $name")

    override fun doubleOrNull(name: String): Double? = str(name)?.toDoubleOrNull()
    override fun boolean(name: String): Boolean =
        str(name)?.toBooleanStrictOrNull() ?: throw IllegalArgumentException("null or invalid boolean: $name")

    override fun booleanOrNull(name: String): Boolean? = str(name)?.toBooleanStrictOrNull()

    // ByteArray support (decode from Base64 or hex string if needed)
    override fun bytes(name: String): ByteArray {
        // For now, assume string encoding - can be enhanced based on driver behavior
        val s = str(name) ?: throw IllegalArgumentException("null bytes: $name")
        return s.encodeToByteArray()
    }

    override fun bytesOrNull(name: String): ByteArray? = str(name)?.encodeToByteArray()
}
