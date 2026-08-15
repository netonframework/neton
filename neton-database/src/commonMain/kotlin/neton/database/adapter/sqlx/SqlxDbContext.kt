package neton.database.adapter.sqlx

import neton.database.api.DbContext
import neton.database.api.DbSessionProvider
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.Table
import neton.database.dsl.SelectAst
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql
import neton.database.sql.Dialect
import neton.database.sql.SqlBuilder
import neton.database.sql.toNamedParams
import kotlin.time.measureTimedValue

/** DbContext implementation backed by a transaction-aware [DbSessionProvider]. */
internal class SqlxDbContext(
    private val sessions: DbSessionProvider,
    override val interceptors: List<QueryInterceptor> = emptyList(),
) : DbContext {
    override val dialect: Dialect get() = sessions.dialect

    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> =
        observe(sql, params.values.toList()) { sessions.current().query(sql, params) }

    override suspend fun execute(sql: String, params: Map<String, Any?>): Long =
        observe(sql, params.values.toList()) { sessions.current().execute(sql, params) }

    override suspend fun query(built: BuiltSql): List<Row> {
        val (sql, params) = built.toNamedParams(dialect)
        return fetchAll(sql, params)
    }

    override suspend fun executeBuilt(built: BuiltSql): Long {
        val (sql, params) = built.toNamedParams(dialect)
        return execute(sql, params)
    }

    override suspend fun inTransaction(): Boolean = sessions.inTransaction()

    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R =
        sessions.transaction { block(this) }

    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> {
        val builder = SelectBuilder(this)
        return builder to builder.from(table)
    }

    private suspend fun <R> observe(sql: String, args: List<Any?>, operation: suspend () -> R): R {
        return try {
            val (result, duration) = measureTimedValue { operation() }
            interceptors.forEach { it.onExecute(sql, args, duration.inWholeMilliseconds) }
            result
        } catch (error: Throwable) {
            interceptors.forEach { it.onError(sql, args, error) }
            throw error
        }
    }
}

internal suspend fun DbContext.selectRows(ast: SelectAst): List<Row> {
    val rewritten = interceptors.fold(ast) { current, interceptor -> interceptor.beforeSelect(current) }
    return query(SqlBuilder(dialect).buildSelect(rewritten))
}

internal suspend fun DbContext.countRows(ast: SelectAst): Long {
    val rewritten = interceptors.fold(ast) { current, interceptor -> interceptor.beforeSelect(current) }
    return query(SqlBuilder(dialect).buildCount(rewritten)).firstOrNull()?.long("count") ?: 0L
}
