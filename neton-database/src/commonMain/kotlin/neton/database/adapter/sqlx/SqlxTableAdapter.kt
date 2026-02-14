package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.api.EntityMeta
import neton.database.api.Order
import neton.database.api.Page
import neton.database.api.Predicate
import neton.database.api.Query
import neton.database.api.QueryScope
import neton.database.api.Table
import neton.database.api.UpdateScope
import neton.database.api.toClausesList
import kotlin.reflect.KProperty1
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * SQLx 的 Table 适配器（表级 CRUD，adapter 包）
 * KSP 生成：object UserTable : Table<User> by SqlxTableAdapter(...)
 */
class SqlxTableAdapter<T : Any>(
    private val meta: EntityMeta<T>,
    private val dbProvider: () -> QueryExecutor = { SqlxDatabase.require() },
    private val mapper: RowMapper<T>,
    private val toParams: (T) -> Map<String, Any?>,
    private val getId: (T) -> Any?
) : Table<T> {

    private val db: QueryExecutor get() = dbProvider()

    fun propToColumn(propName: String): String =
        propName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    private fun isNew(id: Any?): Boolean =
        id == null || (id is Number && id.toLong() == 0L)

    override suspend fun get(id: Any): T? {
        val stmt = Statement.create("SELECT * FROM ${meta.table} WHERE ${meta.idColumn} = :id")
        return db.fetchAll(stmt.bind("id", id), mapper).getOrThrow().firstOrNull()
    }

    override suspend fun findAll(): List<T> {
        val stmt = Statement.create("SELECT * FROM ${meta.table}")
        return db.fetchAll(stmt, mapper).getOrThrow()
    }

    override suspend fun count(): Long {
        val stmt = Statement.create("SELECT COUNT(*) FROM ${meta.table}")
        val rows = db.fetchAll(stmt).getOrThrow()
        return rows.firstOrNull()?.get(0)?.toString()?.toLongOrNull() ?: 0L
    }

    override suspend fun destroy(id: Any): Boolean {
        val stmt = Statement.create("DELETE FROM ${meta.table} WHERE ${meta.idColumn} = :id")
        return db.execute(stmt.bind("id", id)).getOrThrow() > 0
    }

    override suspend fun updateById(id: Any, block: T.() -> T): T? =
        get(id)?.let { save(block(it)) }

    override suspend fun save(entity: T): T {
        val id = getId(entity)
        return if (isNew(id)) insert(entity) else {
            update(entity)
            entity
        }
    }

    override suspend fun saveAll(entities: List<T>): List<T> = transactionBlock {
        entities.map { e ->
            val id = getId(e)
            if (isNew(id)) {
                execute(insertStatement(toParams(e).filterKeys { it != "id" })).getOrThrow()
                e
            } else {
                execute(updateStatement(toParams(e))).getOrThrow()
                e
            }
        }
    }

    override fun where(block: QueryScope<T>.() -> Predicate): Query<T> {
        val scope = QueryScope<T>(::propToColumn)
        val predicate = scope.block()
        return SqlxQuery(this, predicate)
    }

    override suspend fun exists(id: Any): Boolean = get(id) != null
    override suspend fun insert(entity: T): T {
        db.execute(insertStatement(toParams(entity).filterKeys { it != "id" })).getOrThrow()
        return entity
    }
    override suspend fun insertBatch(entities: List<T>): Int = transactionBlock {
        entities.count { e ->
            db.execute(insertStatement(toParams(e).filterKeys { it != "id" })).getOrThrow() > 0
        }
    }
    override suspend fun update(entity: T): Boolean =
        db.execute(updateStatement(toParams(entity))).getOrThrow() > 0
    override suspend fun updateBatch(entities: List<T>): Int =
        entities.count { update(it) }
    override suspend fun delete(entity: T): Boolean {
        val id = getId(entity) ?: return false
        return destroy(id)
    }
    override fun query(): neton.database.api.QueryBuilder<T> =
        SqlxQueryBuilder { findAll() }
    override suspend fun <R> withTransaction(block: suspend Table<T>.() -> R): R =
        transactionBlock { this@SqlxTableAdapter.block() }

    override suspend fun ensureTable() {
        val ddl = buildDdl(meta.table, meta.columns, meta.idColumn)
        db.execute(Statement.create(ddl)).getOrThrow()
    }

    private suspend fun <R> transactionBlock(block: suspend io.github.smyrgeorge.sqlx4k.Transaction.() -> R): R =
        (db as? io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional)?.transaction { block() }
            ?: throw IllegalStateException("Driver does not support transactions")

    private fun buildDdl(table: String, columns: List<String>, idColumn: String): String {
        val dialect = SqlxDatabase.currentDriver()
        val defs = columns.map { c ->
            when (c) {
                idColumn -> when (dialect) {
                    neton.database.config.DatabaseDriver.POSTGRESQL -> "$c SERIAL PRIMARY KEY"
                    neton.database.config.DatabaseDriver.MYSQL -> "$c BIGINT AUTO_INCREMENT PRIMARY KEY"
                    else -> "$c INTEGER PRIMARY KEY AUTOINCREMENT"
                }
                "age", "status" -> "$c INTEGER"
                else -> when {
                    c in listOf("created_at", "updated_at") || c.endsWith("_at") -> "$c BIGINT"
                    dialect == neton.database.config.DatabaseDriver.MYSQL -> "$c VARCHAR(255)"
                    else -> "$c TEXT"
                }
            }
        }
        return "CREATE TABLE IF NOT EXISTS $table (${defs.joinToString(", ")})"
    }

    private fun insertStatement(params: Map<String, Any?>): Statement {
        val cols = params.keys.joinToString(", ")
        val placeholders = params.keys.joinToString(", ") { ":$it" }
        return Statement.create("INSERT INTO ${meta.table} ($cols) VALUES ($placeholders)")
    }
    private fun updateStatement(params: Map<String, Any?>): Statement {
        val set = params.keys.filter { it != meta.idColumn }.joinToString(", ") { "$it = :$it" }
        return Statement.create("UPDATE ${meta.table} SET $set WHERE ${meta.idColumn} = :${meta.idColumn}")
    }

    internal suspend fun executeQuery(predicate: Predicate, orderBy: Pair<String, Boolean>?, limit: Int?, offset: Int?): List<T> {
        val (sql, params) = buildSelect(predicate, orderBy, limit, offset)
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        return db.fetchAll(stmt, mapper).getOrThrow()
    }
    internal suspend fun executeCount(predicate: Predicate): Long {
        val (sql, params) = buildSelect(predicate, null, null, null, count = true)
        val stmt = params.entries.fold(Statement.create(sql)) { s, (k, v) -> s.bind(k, v) }
        val rows = db.fetchAll(stmt).getOrThrow()
        return rows.firstOrNull()?.get(0)?.toString()?.toLongOrNull() ?: 0L
    }
    internal suspend fun executeDelete(predicate: Predicate): Long {
        val (whereSql, whereParams) = buildWhere(predicate)
        val fullSql = "DELETE FROM ${meta.table} WHERE $whereSql"
        val stmt = whereParams.entries.fold(Statement.create(fullSql)) { s, (k, v) -> s.bind(k, v) }
        return db.execute(stmt).getOrThrow().toLong()
    }
    internal suspend fun executeUpdate(predicate: Predicate, setColumns: Map<String, Any?>): Long {
        val (whereSql, whereParams) = buildWhere(predicate)
        val setPart = setColumns.keys.joinToString(", ") { "$it = :set_$it" }
        val fullSql = "UPDATE ${meta.table} SET $setPart WHERE $whereSql"
        val stmt = (setColumns.entries.fold(Statement.create(fullSql)) { s, (k, v) -> s.bind("set_$k", v) })
        val stmt2 = whereParams.entries.fold(stmt) { s, (k, v) -> s.bind(k, v) }
        return db.execute(stmt2).getOrThrow().toLong()
    }
    private fun buildWhere(predicate: Predicate): Pair<String, MutableMap<String, Any?>> {
        val params = mutableMapOf<String, Any?>()
        val clauses = predicate.toClausesList()
        val parts = mutableListOf<String>()
        clauses.forEachIndexed { i, c ->
            when (c.op) {
                "=" -> { val k = "p$i"; parts.add("${c.column} = :$k"); params[k] = c.value }
                "!=" -> { val k = "p$i"; parts.add("${c.column} != :$k"); params[k] = c.value }
                ">" -> { val k = "p$i"; parts.add("${c.column} > :$k"); params[k] = c.value }
                ">=" -> { val k = "p$i"; parts.add("${c.column} >= :$k"); params[k] = c.value }
                "<" -> { val k = "p$i"; parts.add("${c.column} < :$k"); params[k] = c.value }
                "<=" -> { val k = "p$i"; parts.add("${c.column} <= :$k"); params[k] = c.value }
                "LIKE" -> { val k = "p$i"; parts.add("${c.column} LIKE :$k"); params[k] = c.value }
                "IN" -> {
                    val coll = (c.value as? Collection<*>) ?: return@forEachIndexed
                    val placeholders = coll.mapIndexed { j, _ -> ":p${i}_$j" }.joinToString(", ")
                    parts.add("${c.column} IN ($placeholders)")
                    coll.forEachIndexed { j, v -> params["p${i}_$j"] = v }
                }
                "BETWEEN" -> {
                    val r = c.value as? Pair<*, *> ?: return@forEachIndexed
                    val k1 = "p${i}_a"; val k2 = "p${i}_b"
                    parts.add("${c.column} BETWEEN :$k1 AND :$k2")
                    params[k1] = r.first; params[k2] = r.second
                }
                else -> {}
            }
        }
        return if (parts.isEmpty()) "1=1" to params else parts.joinToString(" AND ") to params
    }
    private fun buildSelect(predicate: Predicate, orderBy: Pair<String, Boolean>?, limit: Int?, offset: Int?, count: Boolean = false): Pair<String, Map<String, Any?>> {
        val (whereSql, params) = buildWhere(predicate)
        val select = if (count) "SELECT COUNT(*) FROM ${meta.table}" else "SELECT * FROM ${meta.table}"
        var sql = "$select WHERE $whereSql"
        if (!count && orderBy != null) sql += " ORDER BY ${orderBy.first} ${if (orderBy.second) "ASC" else "DESC"}"
        if (!count && limit != null) sql += " LIMIT $limit"
        if (!count && offset != null) sql += " OFFSET $offset"
        return sql to params
    }
}

internal class SqlxQuery<T : Any>(
    private val table: SqlxTableAdapter<T>,
    private val predicate: Predicate,
    private var orderByColumn: String? = null,
    private var orderAsc: Boolean = true,
    private var limitN: Int? = null,
    private var offsetN: Int? = null,
    private var pageNum: Int? = null,
    private var pageSize: Int? = null
) : Query<T> {
    override fun orderBy(prop: KProperty1<T, *>, ascending: Boolean): Query<T> {
        orderByColumn = table.propToColumn(prop.name)
        orderAsc = ascending
        return this
    }
    override fun orderBy(order: Pair<KProperty1<T, *>, Boolean>): Query<T> =
        orderBy(order.first, order.second)
    override fun orderBy(vararg orders: Order<T>): Query<T> {
        if (orders.isNotEmpty()) {
            orderByColumn = table.propToColumn(orders.first().property.name)
            orderAsc = orders.first().asc
        }
        return this
    }
    override fun limit(n: Int): Query<T> { limitN = n; return this }
    override fun offset(n: Int): Query<T> { offsetN = n; return this }
    override fun page(page: Int, size: Int): Query<T> {
        pageNum = page
        pageSize = size
        offsetN = (page - 1) * size
        limitN = size
        return this
    }
    private fun effectiveLimit(): Int? = limitN
    private fun effectiveOffset(): Int? = offsetN
    override suspend fun list(): List<T> =
        table.executeQuery(predicate, if (orderByColumn != null) orderByColumn!! to orderAsc else null, effectiveLimit(), effectiveOffset())
    override suspend fun first(): T? =
        table.executeQuery(predicate, if (orderByColumn != null) orderByColumn!! to orderAsc else null, 1, effectiveOffset()).firstOrNull()
    override suspend fun firstOrNull(): T? = first()
    override suspend fun one(): T {
        val list = table.executeQuery(predicate, if (orderByColumn != null) orderByColumn!! to orderAsc else null, 2, effectiveOffset())
        return when (list.size) {
            1 -> list.single()
            0 -> throw NoSuchElementException("Query returned no result")
            else -> throw IllegalStateException("Query returned ${list.size} results, expected exactly one")
        }
    }
    override suspend fun oneOrNull(): T? = firstOrNull()
    override suspend fun count(): Long = table.executeCount(predicate)
    override suspend fun exists(): Boolean = table.executeCount(predicate) > 0
    override fun flow(): Flow<T> = flow { list().forEach { emit(it) } }
    override suspend fun delete(): Long = table.executeDelete(predicate)
    override suspend fun update(block: UpdateScope<T>.() -> Unit): Long {
        val setMap = mutableMapOf<String, Any?>()
        val scope = object : UpdateScope<T> {
            override fun <V> set(prop: KProperty1<T, V>, value: V) {
                setMap[table.propToColumn(prop.name)] = value
            }
        }
        scope.block()
        return table.executeUpdate(predicate, setMap)
    }
    override suspend fun listPage(): Page<T> {
        val total = table.executeCount(predicate)
        val p = pageNum ?: 1
        val size = pageSize ?: 20
        val items = table.executeQuery(predicate, if (orderByColumn != null) orderByColumn!! to orderAsc else null, size, (p - 1) * size)
        return Page.of(items, total, p, size)
    }
}
