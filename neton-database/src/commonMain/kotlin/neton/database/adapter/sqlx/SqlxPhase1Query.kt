package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.api.EntityQuery
import neton.database.api.Page
import neton.database.api.ProjectionQuery
import neton.database.api.Row
import neton.database.api.SoftDeleteConfig
import neton.database.dsl.ColumnRef
import neton.database.dsl.QueryAst
import neton.database.dsl.QueryMeta
import neton.database.dsl.TableMeta
import neton.database.dsl.normalizeForSoftDelete
import neton.database.sql.BuiltSql
import neton.database.sql.SqlBuilder
import neton.database.sql.toNamedParams

/**
 * Phase 1 EntityQuery：持有 QueryAst，用 SqlBuilder 生成 SQL，sqlx 执行。
 */
internal class SqlxEntityQuery<T : Any>(
    private val adapter: SqlxTableAdapter<T, *>,
    private val ast: QueryAst<T>,
    private val softDeleteConfig: SoftDeleteConfig?
) : EntityQuery<T> {

    override suspend fun list(): List<T> {
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        return adapter.executePhase1Select(built, adapter.phase1Mapper())
    }

    override suspend fun count(): Long {
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
        val built = adapter.phase1SqlBuilder().buildCount(normalized)
        return adapter.executePhase1Count(built)
    }

    override suspend fun page(page: Int, size: Int): Page<T> {
        val total = count()
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
            .copy(limit = size, offset = (page - 1).coerceAtLeast(0) * size)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        val items = adapter.executePhase1Select(built, adapter.phase1Mapper())
        return Page.of(items, total, page, size)
    }

    override fun select(vararg columnNames: String): ProjectionQuery {
        val projection = columnNames.map { ColumnRef(it) }
        val newAst = ast.copy(projection = projection)
        return SqlxProjectionQuery(adapter, newAst, softDeleteConfig)
    }
}

/**
 * Phase 1 ProjectionQuery：SELECT 指定列，返回 List<Row>。
 */
internal class SqlxProjectionQuery(
    private val adapter: SqlxTableAdapter<*, *>,
    private val ast: QueryAst<*>,
    private val softDeleteConfig: SoftDeleteConfig?
) : ProjectionQuery {

    override suspend fun rows(): List<Row> {
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        return adapter.executePhase1SelectRows(built)
    }

    override suspend fun count(): Long {
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
        val built = adapter.phase1SqlBuilder().buildCount(normalized)
        return adapter.executePhase1Count(built)
    }

    override suspend fun page(page: Int, size: Int): Page<Row> {
        val total = count()
        val normalized = ast.normalizeForSoftDelete(softDeleteConfig)
            .copy(limit = size, offset = (page - 1).coerceAtLeast(0) * size)
        val built = adapter.phase1SqlBuilder().buildSelect(normalized)
        val items = adapter.executePhase1SelectRows(built)
        return Page.of(items, total, page, size)
    }
}

internal fun SqlxTableAdapter<*, *>.phase1Dialect(): neton.database.sql.Dialect =
    when (neton.database.adapter.sqlx.SqlxDatabase.currentDriver()) {
        neton.database.config.DatabaseDriver.POSTGRESQL -> neton.database.sql.PostgresDialect
        neton.database.config.DatabaseDriver.MYSQL -> neton.database.sql.MySqlDialect
        else -> neton.database.sql.SqliteDialect
    }

internal fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.phase1SqlBuilder(): SqlBuilder =
    SqlBuilder(phase1Dialect())

internal fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.phase1Mapper(): RowMapper<T> = phase1MapperUnsafe

internal suspend fun <T : Any, ID : Any> SqlxTableAdapter<T, ID>.executePhase1Select(
    built: BuiltSql,
    rowMapper: RowMapper<T>
): List<T> {
    val (sql, params) = built.toNamedParams(phase1Dialect())
    val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
    return phase1Db.fetchAll(stmt, rowMapper).getOrThrow()
}

internal suspend fun SqlxTableAdapter<*, *>.executePhase1Count(built: BuiltSql): Long {
    val (sql, params) = built.toNamedParams(phase1Dialect())
    val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
    val rows = phase1Db.fetchAll(stmt).getOrThrow()
    return rows.firstOrNull()?.get(0)?.asString()?.toLongOrNull() ?: 0L
}

internal suspend fun SqlxTableAdapter<*, *>.executePhase1SelectRows(built: BuiltSql): List<Row> {
    val (sql, params) = built.toNamedParams(phase1Dialect())
    val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
    val rows = phase1Db.fetchAll(stmt).getOrThrow()
    return rows.map { Phase1Row(it) }
}

/** Phase1 投影查询用 Row 适配，不与其他文件中的 SqlxRow 重名 */
private class Phase1Row(private val row: io.github.smyrgeorge.sqlx4k.ResultSet.Row) : Row {
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
}
