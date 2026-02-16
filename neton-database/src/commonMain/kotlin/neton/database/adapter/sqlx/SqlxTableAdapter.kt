package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.api.AutoFillConfig
import neton.database.api.AutoFillProvider
import neton.database.api.EntityMeta
import neton.database.api.EntityQuery
import neton.database.api.Page
import neton.database.api.Predicate
import neton.database.api.SoftDeleteConfig
import neton.database.api.Table
import neton.database.api.toClausesList
import neton.database.dsl.ColumnRef
import neton.database.dsl.QueryMeta
import neton.database.dsl.TableMeta

/**
 * SQLx 的 Table 适配器（表级 CRUD，adapter 包）
 * KSP 生成：object UserTable : Table<User, Long> by SqlxTableAdapter<User, Long>(...)
 */
class SqlxTableAdapter<T : Any, ID : Any>(
    private val meta: EntityMeta<T>,
    private val dbProvider: () -> QueryExecutor = { SqlxDatabase.require() },
    private val mapper: RowMapper<T>,
    private val toParams: (T) -> Map<String, Any?>,
    private val getId: (T) -> ID?,
    private val softDeleteConfig: SoftDeleteConfig? = null,
    private val autoFillConfig: AutoFillConfig? = null,
    private val autoFillProvider: AutoFillProvider? = null
) : Table<T, ID> {

    private val db: QueryExecutor get() = dbProvider()
    internal val phase1Db: QueryExecutor get() = db
    internal val phase1MapperUnsafe: RowMapper<T> get() = mapper

    fun propToColumn(propName: String): String =
        propName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    private fun isNew(id: ID?): Boolean =
        id == null || (id is Number && id.toLong() == 0L)

    override suspend fun get(id: ID): T? {
        val whereClause = if (softDeleteConfig != null)
            "${meta.idColumn} = :id AND ${softDeleteConfig.deletedColumn} = :deleted"
        else
            "${meta.idColumn} = :id"
        val stmt = Statement.create("SELECT * FROM ${meta.table} WHERE $whereClause")
        val bound = if (softDeleteConfig != null) stmt.bind("id", id).bind("deleted", false) else stmt.bind("id", id)
        return db.fetchAll(bound, mapper).getOrThrow().firstOrNull()
    }

    override suspend fun findAll(): List<T> {
        val (sql, bind) = if (softDeleteConfig != null) {
            "SELECT * FROM ${meta.table} WHERE ${softDeleteConfig.deletedColumn} = :deleted" to { s: Statement ->
                s.bind(
                    "deleted",
                    false
                )
            }
        } else {
            "SELECT * FROM ${meta.table}" to { s: Statement -> s }
        }
        val stmt = bind(Statement.create(sql))
        return db.fetchAll(stmt, mapper).getOrThrow()
    }

    override suspend fun count(): Long {
        val stmt = Statement.create("SELECT COUNT(*) FROM ${meta.table}")
        val rows = db.fetchAll(stmt).getOrThrow()
        return rows.firstOrNull()?.get(0)?.toString()?.toLongOrNull() ?: 0L
    }

    override suspend fun destroy(id: ID): Boolean {
        return if (softDeleteConfig != null) {
            executeSoftDeleteById(id)
        } else {
            val stmt = Statement.create("DELETE FROM ${meta.table} WHERE ${meta.idColumn} = :id")
            db.execute(stmt.bind("id", id)).getOrThrow() > 0
        }
    }

    override suspend fun destroyMany(ids: Collection<ID>): Int {
        if (ids.isEmpty()) return 0
        return if (softDeleteConfig != null) {
            executeSoftDeleteMany(ids)
        } else {
            val placeholders = ids.mapIndexed { i, _ -> ":id$i" }.joinToString(", ")
            val stmt =
                ids.foldIndexed(Statement.create("DELETE FROM ${meta.table} WHERE ${meta.idColumn} IN ($placeholders)")) { i, s, v ->
                    s.bind(
                        "id$i",
                        v
                    )
                }
            db.execute(stmt).getOrThrow().toInt()
        }
    }

    private suspend fun executeSoftDeleteById(id: ID): Boolean {
        val cfg = softDeleteConfig!!
        val now = cfg.deletedAtColumn?.let { autoFillProvider?.nowMillis() ?: 0L } ?: 0L
        val setParts = mutableListOf<String>("${cfg.deletedColumn} = :deleted")
        val stmt0 =
            Statement.create("UPDATE ${meta.table} SET ${setParts.joinToString(", ")} WHERE ${meta.idColumn} = :id")
        var stmt = stmt0.bind("deleted", true).bind("id", id)
        cfg.deletedAtColumn?.let { col ->
            val sql =
                "UPDATE ${meta.table} SET ${cfg.deletedColumn} = :deleted, $col = :deletedAt WHERE ${meta.idColumn} = :id"
            stmt = Statement.create(sql).bind("deleted", true).bind("deletedAt", now).bind("id", id)
        }
        return db.execute(stmt).getOrThrow() > 0
    }

    private suspend fun executeSoftDeleteMany(ids: Collection<ID>): Int {
        val cfg = softDeleteConfig!!
        val now = cfg.deletedAtColumn?.let { autoFillProvider?.nowMillis() ?: 0L } ?: 0L
        val placeholders = ids.mapIndexed { i, _ -> ":id$i" }.joinToString(", ")
        val setClause = cfg.deletedAtColumn?.let { "${cfg.deletedColumn} = :deleted, $it = :deletedAt" }
            ?: "${cfg.deletedColumn} = :deleted"
        val sql = "UPDATE ${meta.table} SET $setClause WHERE ${meta.idColumn} IN ($placeholders)"
        var stmt = Statement.create(sql).bind("deleted", true)
        if (cfg.deletedAtColumn != null) stmt = stmt.bind("deletedAt", now)
        ids.forEachIndexed { i, v -> stmt = stmt.bind("id$i", v) }
        return db.execute(stmt).getOrThrow().toInt()
    }

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
                val params = toParams(e).filterKeys { it != "id" }
                execute(insertStatement(mergeAutoFillForInsert(params))).getOrThrow()
                e
            } else {
                execute(updateStatement(mergeAutoFillForUpdate(toParams(e)))).getOrThrow()
                e
            }
        }
    }

    override fun query(block: neton.database.dsl.QueryScope<T>.() -> Unit): EntityQuery<T> {
        val queryMeta = QueryMeta<T>(TableMeta(meta.table))
        val scope = neton.database.dsl.QueryScope(queryMeta)
        scope.block()
        val deletedCol = softDeleteConfig?.let { ColumnRef(it.deletedColumn) }
        return SqlxEntityQuery(this, scope.build(), deletedCol)
    }

    override suspend fun many(ids: Collection<ID>): List<T> =
        query {
            where {
                neton.database.dsl.Predicate.In(
                    ColumnRef(meta.idColumn),
                    ids.map { it as Any? }.toList()
                )
            }
        }.list()

    override suspend fun oneWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): T? =
        query { where(block) }.list().firstOrNull()

    override suspend fun existsWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): Boolean =
        query { where(block) }.count() > 0

    override suspend fun exists(id: ID): Boolean = get(id) != null
    override suspend fun insert(entity: T): T {
        val params = toParams(entity).filterKeys { it != "id" }
        val withAutoFill = mergeAutoFillForInsert(params)
        db.execute(insertStatement(withAutoFill)).getOrThrow()
        return entity
    }

    override suspend fun insertBatch(entities: List<T>): Int = transactionBlock {
        entities.count { e ->
            val params = toParams(e).filterKeys { it != "id" }
            db.execute(insertStatement(mergeAutoFillForInsert(params))).getOrThrow() > 0
        }
    }

    override suspend fun update(entity: T): Boolean =
        db.execute(updateStatement(mergeAutoFillForUpdate(toParams(entity)))).getOrThrow() > 0

    override suspend fun updateBatch(entities: List<T>): Int =
        entities.count { update(it) }

    override suspend fun delete(entity: T): Boolean {
        val id = getId(entity) ?: return false
        return destroy(id)
    }

    override fun query(): neton.database.api.QueryBuilder<T> =
        SqlxQueryBuilder { findAll() }

    override suspend fun <R> transaction(block: suspend Table<T, ID>.() -> R): R =
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

    private fun mergeAutoFillForInsert(params: Map<String, Any?>): Map<String, Any?> {
        if (autoFillConfig == null || autoFillProvider == null) return params
        val now = autoFillProvider.nowMillis()
        val userId = autoFillProvider.currentUserId()
        return params + mapOf(
            autoFillConfig.createdAtColumn to now,
            autoFillConfig.updatedAtColumn to now,
            autoFillConfig.createdByColumn to userId,
            autoFillConfig.updatedByColumn to userId
        )
    }

    private fun mergeAutoFillForUpdate(params: Map<String, Any?>): Map<String, Any?> {
        if (autoFillConfig == null || autoFillProvider == null) return params
        val now = autoFillProvider.nowMillis()
        val userId = autoFillProvider.currentUserId()
        return params + mapOf(
            autoFillConfig.updatedAtColumn to now,
            autoFillConfig.updatedByColumn to userId
        )
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

    internal suspend fun executeQuery(
        predicate: Predicate,
        orderBy: Pair<String, Boolean>?,
        limit: Int?,
        offset: Int?
    ): List<T> {
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
                "=" -> {
                    val k = "p$i"; parts.add("${c.column} = :$k"); params[k] = c.value
                }

                "!=" -> {
                    val k = "p$i"; parts.add("${c.column} != :$k"); params[k] = c.value
                }

                ">" -> {
                    val k = "p$i"; parts.add("${c.column} > :$k"); params[k] = c.value
                }

                ">=" -> {
                    val k = "p$i"; parts.add("${c.column} >= :$k"); params[k] = c.value
                }

                "<" -> {
                    val k = "p$i"; parts.add("${c.column} < :$k"); params[k] = c.value
                }

                "<=" -> {
                    val k = "p$i"; parts.add("${c.column} <= :$k"); params[k] = c.value
                }

                "LIKE" -> {
                    val k = "p$i"; parts.add("${c.column} LIKE :$k"); params[k] = c.value
                }

                "IN" -> {
                    val coll = (c.value as? Collection<*>) ?: return@forEachIndexed
                    val placeholders = coll.mapIndexed { j, _ -> ":p${i}_$j" }.joinToString(", ")
                    parts.add("${c.column} IN ($placeholders)")
                    coll.forEachIndexed { j, v -> params["p${i}_$j"] = v }
                }

                "BETWEEN" -> {
                    val r = c.value as? Pair<*, *> ?: return@forEachIndexed
                    val k1 = "p${i}_a";
                    val k2 = "p${i}_b"
                    parts.add("${c.column} BETWEEN :$k1 AND :$k2")
                    params[k1] = r.first; params[k2] = r.second
                }

                else -> {}
            }
        }
        return if (parts.isEmpty()) "1=1" to params else parts.joinToString(" AND ") to params
    }

    private fun buildSelect(
        predicate: Predicate,
        orderBy: Pair<String, Boolean>?,
        limit: Int?,
        offset: Int?,
        count: Boolean = false
    ): Pair<String, Map<String, Any?>> {
        val (whereSql, params) = buildWhere(predicate)
        val select = if (count) "SELECT COUNT(*) FROM ${meta.table}" else "SELECT * FROM ${meta.table}"
        var sql = "$select WHERE $whereSql"
        if (!count && orderBy != null) sql += " ORDER BY ${orderBy.first} ${if (orderBy.second) "ASC" else "DESC"}"
        if (!count && limit != null) sql += " LIMIT $limit"
        if (!count && offset != null) sql += " OFFSET $offset"
        return sql to params
    }
}
