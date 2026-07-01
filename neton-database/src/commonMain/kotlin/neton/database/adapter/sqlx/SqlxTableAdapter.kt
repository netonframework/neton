package neton.database.adapter.sqlx

import neton.database.api.AutoFillConfig
import neton.database.api.DbContext
import neton.database.api.EntityMapper
import neton.database.api.EntityMeta
import neton.database.api.EntityQuery
import neton.database.api.Predicate
import neton.database.api.SoftDeleteConfig
import neton.database.api.Table
import neton.database.api.toClausesList
import neton.database.dsl.ColumnRef
import neton.database.dsl.QueryMeta
import neton.database.dsl.TableMeta

private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** Single-table adapter. All execution goes through the transaction-aware [DbContext]. */
class SqlxTableAdapter<T : Any, ID : Any>(
    private val meta: EntityMeta<T>,
    private val dbProvider: () -> DbContext = { SqlxDatabase.requireContext() },
    private val mapper: EntityMapper<T>,
    private val toParams: (T) -> Map<String, Any?>,
    private val softDeleteConfig: SoftDeleteConfig? = null,
    private val autoFillConfig: AutoFillConfig? = null,
    private val autoGenerateId: Boolean = true,
) : Table<T, ID> {
    internal val db: DbContext get() = dbProvider()
    internal val entityMapper: EntityMapper<T> get() = mapper

    fun propToColumn(propName: String): String =
        propName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    override suspend fun get(id: ID): T? {
        val where = if (softDeleteConfig != null) {
            "${meta.idColumn} = :id AND ${softDeleteConfig.deletedColumn} = :deleted"
        } else {
            "${meta.idColumn} = :id"
        }
        val params = mutableMapOf<String, Any?>("id" to id)
        softDeleteConfig?.let { params["deleted"] = it.notDeletedValue }
        return db.fetchAll("SELECT * FROM ${meta.table} WHERE $where", params)
            .firstOrNull()
            ?.let(mapper::map)
    }

    override suspend fun findAll(): List<T> {
        val sql: String
        val params: Map<String, Any?>
        if (softDeleteConfig != null) {
            sql = "SELECT * FROM ${meta.table} WHERE ${softDeleteConfig.deletedColumn} = :deleted"
            params = mapOf("deleted" to softDeleteConfig.notDeletedValue)
        } else {
            sql = "SELECT * FROM ${meta.table}"
            params = emptyMap()
        }
        return db.fetchAll(sql, params).map(mapper::map)
    }

    override suspend fun count(): Long {
        val where = softDeleteConfig?.let { " WHERE ${it.deletedColumn} = :deleted" }.orEmpty()
        val params = softDeleteConfig?.let { mapOf("deleted" to it.notDeletedValue) }.orEmpty()
        return db.fetchAll("SELECT COUNT(*) AS count FROM ${meta.table}$where", params)
            .firstOrNull()
            ?.long("count")
            ?: 0L
    }

    override suspend fun destroy(id: ID): Boolean = if (softDeleteConfig != null) {
        executeSoftDeleteById(id)
    } else {
        db.execute("DELETE FROM ${meta.table} WHERE ${meta.idColumn} = :id", mapOf("id" to id)) > 0
    }

    override suspend fun destroyMany(ids: Collection<ID>): Int {
        if (ids.isEmpty()) return 0
        if (softDeleteConfig != null) return executeSoftDeleteMany(ids)

        val placeholders = ids.indices.joinToString(", ") { ":id$it" }
        val params = ids.mapIndexed { index, id -> "id$index" to id }.toMap()
        return db.execute(
            "DELETE FROM ${meta.table} WHERE ${meta.idColumn} IN ($placeholders)",
            params,
        ).toInt()
    }

    private suspend fun executeSoftDeleteById(id: ID): Boolean {
        val config = requireNotNull(softDeleteConfig)
        val params = linkedMapOf<String, Any?>(
            "deleted" to config.deletedValue(),
            "id" to id,
        )
        val assignments = mutableListOf("${config.deletedColumn} = :deleted")
        config.deletedAtColumn?.let {
            assignments += "$it = :deletedAt"
            params["deletedAt"] = currentTimeMillis()
        }
        return db.execute(
            "UPDATE ${meta.table} SET ${assignments.joinToString(", ")} WHERE ${meta.idColumn} = :id",
            params,
        ) > 0
    }

    private suspend fun executeSoftDeleteMany(ids: Collection<ID>): Int {
        val config = requireNotNull(softDeleteConfig)
        val placeholders = ids.indices.joinToString(", ") { ":id$it" }
        val params = linkedMapOf<String, Any?>("deleted" to config.deletedValue())
        val assignments = mutableListOf("${config.deletedColumn} = :deleted")
        config.deletedAtColumn?.let {
            assignments += "$it = :deletedAt"
            params["deletedAt"] = currentTimeMillis()
        }
        ids.forEachIndexed { index, id -> params["id$index"] = id }
        return db.execute(
            "UPDATE ${meta.table} SET ${assignments.joinToString(", ")} " +
                "WHERE ${meta.idColumn} IN ($placeholders)",
            params,
        ).toInt()
    }

    override fun query(block: neton.database.dsl.QueryScope<T>.() -> Unit): EntityQuery<T> {
        val scope = neton.database.dsl.QueryScope(QueryMeta<T>(TableMeta(meta.table)))
        scope.block()
        return SqlxEntityQuery(this, scope.build(), softDeleteConfig)
    }

    override suspend fun many(ids: Collection<ID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return query {
        where {
            neton.database.dsl.Predicate.In(ColumnRef(meta.idColumn), ids.map { it as Any? })
        }
        }.list()
    }

    override suspend fun oneWhere(
        block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate,
    ): T? = query { where(block) }.list().firstOrNull()

    override suspend fun existsWhere(
        block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate,
    ): Boolean = query { where(block) }.count() > 0

    override suspend fun exists(id: ID): Boolean = get(id) != null

    override suspend fun insert(entity: T): T {
        val raw = toParams(entity)
        val params = if (autoGenerateId) raw.filterKeys { it != meta.idColumn } else raw
        val (sql, values) = insertStatement(mergeAutoFillForInsert(params))
        if (!autoGenerateId) {
            db.execute(sql, values)
            return entity
        }

        val row = when (db.dialect.name) {
            "postgres", "sqlite" -> db.fetchAll("$sql RETURNING *", values).singleOrNull()
            "mysql" -> db.transaction {
                execute(sql, values)
                fetchAll(
                    "SELECT * FROM ${meta.table} WHERE ${meta.idColumn} = LAST_INSERT_ID()",
                ).singleOrNull()
            }
            else -> error("Auto-generated ids are not supported for dialect '${db.dialect.name}'")
        } ?: error("Insert into '${meta.table}' did not return the generated row")

        return mapper.map(row)
    }

    override suspend fun insertBatch(entities: List<T>): Int = db.transaction {
        entities.count { insert(it).let { true } }
    }

    override suspend fun update(entity: T): Boolean {
        val params = mergeAutoFillForUpdate(toParams(entity))
        val (sql, values) = updateStatement(params)
        return db.execute(sql, values) > 0
    }

    override suspend fun updateBatch(entities: List<T>): Int = db.transaction {
        entities.count { update(it) }
    }

    override fun query(): neton.database.api.QueryBuilder<T> = SqlxQueryBuilder { findAll() }

    override suspend fun ensureTable() {
        db.execute(buildDdl(meta.table, meta.columns, meta.idColumn))
    }

    private fun buildDdl(table: String, columns: List<String>, idColumn: String): String {
        val types = meta.columnTypes
        val definitions = columns.map { column ->
            if (column == idColumn) {
                when (db.dialect.name) {
                    "postgres" -> "$column SERIAL PRIMARY KEY"
                    "mysql" -> "$column BIGINT AUTO_INCREMENT PRIMARY KEY"
                    else -> "$column INTEGER PRIMARY KEY AUTOINCREMENT"
                }
            } else {
                "$column ${types[column] ?: if (db.dialect.name == "mysql") "VARCHAR(255)" else "TEXT"}"
            }
        }
        return "CREATE TABLE IF NOT EXISTS $table (${definitions.joinToString(", ")})"
    }

    private fun mergeAutoFillForInsert(params: Map<String, Any?>): Map<String, Any?> {
        val config = autoFillConfig ?: return params
        val now = currentTimeMillis()
        return params + buildMap {
            config.createdAtColumn?.let { put(it, now) }
            config.updatedAtColumn?.let { put(it, now) }
        }
    }

    private fun mergeAutoFillForUpdate(params: Map<String, Any?>): Map<String, Any?> {
        val config = autoFillConfig ?: return params
        return params + buildMap {
            config.updatedAtColumn?.let { put(it, currentTimeMillis()) }
        }
    }

    private fun insertStatement(params: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val columns = params.keys.joinToString(", ")
        val placeholders = params.keys.joinToString(", ") { ":$it" }
        return "INSERT INTO ${meta.table} ($columns) VALUES ($placeholders)" to params
    }

    private fun updateStatement(params: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        require(meta.idColumn in params) { "Update requires primary key column '${meta.idColumn}'" }
        val assignments = params.keys.filter { it != meta.idColumn }.joinToString(", ") { "$it = :$it" }
        require(assignments.isNotEmpty()) { "Update requires at least one non-primary-key column" }
        return "UPDATE ${meta.table} SET $assignments WHERE ${meta.idColumn} = :${meta.idColumn}" to params
    }

    internal suspend fun executeQuery(
        predicate: Predicate,
        orderBy: Pair<String, Boolean>?,
        limit: Int?,
        offset: Int?,
    ): List<T> {
        val (sql, params) = buildSelect(predicate, orderBy, limit, offset)
        return db.fetchAll(sql, params).map(mapper::map)
    }

    internal suspend fun executeCount(predicate: Predicate): Long {
        val (sql, params) = buildSelect(predicate, null, null, null, count = true)
        return db.fetchAll(sql, params).firstOrNull()?.long("count") ?: 0L
    }

    internal suspend fun executeDelete(predicate: Predicate): Long {
        val (where, params) = buildWhere(predicate)
        return db.execute("DELETE FROM ${meta.table} WHERE $where", params)
    }

    internal suspend fun executeUpdate(predicate: Predicate, setColumns: Map<String, Any?>): Long {
        val (where, whereParams) = buildWhere(predicate)
        val assignments = setColumns.keys.joinToString(", ") { "$it = :set_$it" }
        val params = setColumns.mapKeys { (key, _) -> "set_$key" } + whereParams
        return db.execute("UPDATE ${meta.table} SET $assignments WHERE $where", params)
    }

    private fun buildWhere(predicate: Predicate): Pair<String, MutableMap<String, Any?>> {
        val params = mutableMapOf<String, Any?>()
        val parts = predicate.toClausesList().mapIndexedNotNull { index, clause ->
            when (clause.op) {
                "=", "!=", ">", ">=", "<", "<=", "LIKE" -> {
                    val key = "p$index"
                    params[key] = clause.value
                    "${clause.column} ${clause.op} :$key"
                }
                "IN" -> {
                    val values = clause.value as? Collection<*> ?: return@mapIndexedNotNull null
                    if (values.isEmpty()) return@mapIndexedNotNull "1 = 0"
                    values.forEachIndexed { itemIndex, value -> params["p${index}_$itemIndex"] = value }
                    "${clause.column} IN (${values.indices.joinToString(", ") { ":p${index}_$it" }})"
                }
                "BETWEEN" -> {
                    val range = clause.value as? Pair<*, *> ?: return@mapIndexedNotNull null
                    params["p${index}_a"] = range.first
                    params["p${index}_b"] = range.second
                    "${clause.column} BETWEEN :p${index}_a AND :p${index}_b"
                }
                else -> null
            }
        }
        return (parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ") ?: "1=1") to params
    }

    private fun buildSelect(
        predicate: Predicate,
        orderBy: Pair<String, Boolean>?,
        limit: Int?,
        offset: Int?,
        count: Boolean = false,
    ): Pair<String, Map<String, Any?>> {
        val (where, params) = buildWhere(predicate)
        var sql = if (count) "SELECT COUNT(*) AS count FROM ${meta.table}" else "SELECT * FROM ${meta.table}"
        sql += " WHERE $where"
        if (!count && orderBy != null) sql += " ORDER BY ${orderBy.first} ${if (orderBy.second) "ASC" else "DESC"}"
        if (!count && limit != null) sql += " LIMIT $limit"
        if (!count && offset != null) sql += " OFFSET $offset"
        return sql to params
    }
}

private fun SoftDeleteConfig.deletedValue(): Any = when (notDeletedValue) {
    is Byte, is Short, is Int, is Long -> 1
    else -> true
}
