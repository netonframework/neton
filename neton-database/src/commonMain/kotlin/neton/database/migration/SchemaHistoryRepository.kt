package neton.database.migration

import neton.database.api.DbContext

/**
 * 全局 history 表的 DDL + CRUD,跨 module 共享。SPEC §0.4 / §六:
 *   - 表名 = [MigrationConfig.historyTable](可配,默认 `neton_schema_history`)
 *   - 列: module_id / version / description / checksum / installed_at /
 *         execution_ms / success / error_message
 *   - UNIQUE(module_id, version)
 *
 * 所有 SQL 走 [DbContext.execute] / [DbContext.fetchAll](raw 逃生口),
 * 不依赖 sqlx4k driver 类型 — engine 与 application serve 共享同一个
 * DbContext / driver(NETON-DB-VARIANT 单 driver 约束)。
 *
 * 注意: 因 history 表名来自 config,无法用普通参数绑定(SQL identifier),
 * 表名拼接走静态校验([validateTableName])。其它字段值仍走 escapeSql,
 * 写入次数极低(每个 migration 一次)。
 */
internal class SchemaHistoryRepository(
    private val db: DbContext,
    private val config: MigrationConfig,
) {
    private val table: String = validateTableName(config.historyTable)
    private val dialect: MigrationDialect = config.dialect

    data class Row(
        val moduleId: String,
        val version: String,
        val description: String,
        val checksum: String,
        val installedAt: Long,
        val executionMs: Long,
        val success: Boolean,
        val errorMessage: String?,
    )

    /** 探测表是否存在(三方言各自查 catalog)。 */
    suspend fun exists(): Boolean {
        val sql = when (dialect) {
            MigrationDialect.SQLITE ->
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1"
            MigrationDialect.POSTGRESQL ->
                "SELECT tablename FROM pg_tables WHERE tablename='$table' LIMIT 1"
            MigrationDialect.MYSQL ->
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = '$table' LIMIT 1"
        }
        return db.fetchAll(sql).isNotEmpty()
    }

    /** 确保 history 表存在(CREATE TABLE IF NOT EXISTS)。 */
    suspend fun ensureExists() {
        db.execute(ddl())
    }

    /** 列出全部 history 行,按 (module_id, version) 升序。 */
    suspend fun listAll(): List<Row> {
        val sql = """
            SELECT module_id, version, description, checksum,
                   installed_at, execution_ms, success, error_message
            FROM $table
            ORDER BY module_id ASC, version ASC
        """.trimIndent()
        return db.fetchAll(sql).map { row ->
            Row(
                moduleId = row.string("module_id"),
                version = row.string("version"),
                description = row.stringOrNull("description") ?: "",
                checksum = row.stringOrNull("checksum") ?: "",
                installedAt = row.longOrNull("installed_at") ?: 0L,
                executionMs = row.longOrNull("execution_ms") ?: 0L,
                success = parseSuccess(row.stringOrNull("success")),
                errorMessage = row.stringOrNull("error_message"),
            )
        }
    }

    /**
     * driver 返回 success 列的原始字符串形式因方言而异:
     *  - PG 返回 "t" / "f" (或 "true"/"false")
     *  - MySQL TINYINT 返回 "1" / "0"
     *  - SQLite INTEGER 返回 "1" / "0"
     * 统一兼容三种形式。
     */
    private fun parseSuccess(raw: String?): Boolean {
        if (raw == null) return false
        return when (raw.lowercase()) {
            "t", "true", "1" -> true
            else -> false
        }
    }

    /** 插入一条 history 记录。 */
    suspend fun insert(row: Row) {
        val sql = """
            INSERT INTO $table
              (module_id, version, description, checksum, installed_at, execution_ms, success, error_message)
            VALUES
              ('${esc(row.moduleId)}', '${esc(row.version)}', '${esc(row.description)}', '${esc(row.checksum)}',
               ${row.installedAt}, ${row.executionMs},
               ${boolLit(row.success)},
               ${row.errorMessage?.let { "'${esc(it)}'" } ?: "NULL"})
        """.trimIndent()
        db.execute(sql)
    }

    private fun boolLit(v: Boolean): String = when (dialect) {
        MigrationDialect.POSTGRESQL -> if (v) "TRUE" else "FALSE"
        MigrationDialect.MYSQL, MigrationDialect.SQLITE -> if (v) "1" else "0"
    }

    private fun esc(s: String): String = s.replace("'", "''")

    private fun ddl(): String = when (dialect) {
        MigrationDialect.SQLITE -> """
            CREATE TABLE IF NOT EXISTS $table (
              module_id     TEXT NOT NULL,
              version       TEXT NOT NULL,
              description   TEXT,
              checksum      TEXT,
              installed_at  INTEGER NOT NULL,
              execution_ms  INTEGER NOT NULL,
              success       INTEGER NOT NULL,
              error_message TEXT,
              UNIQUE(module_id, version)
            )
        """.trimIndent()

        MigrationDialect.POSTGRESQL -> """
            CREATE TABLE IF NOT EXISTS $table (
              module_id     VARCHAR(64)  NOT NULL,
              version       VARCHAR(32)  NOT NULL,
              description   VARCHAR(255),
              checksum      VARCHAR(128),
              installed_at  BIGINT       NOT NULL,
              execution_ms  BIGINT       NOT NULL,
              success       BOOLEAN      NOT NULL,
              error_message TEXT,
              UNIQUE(module_id, version)
            )
        """.trimIndent()

        MigrationDialect.MYSQL -> """
            CREATE TABLE IF NOT EXISTS $table (
              module_id     VARCHAR(64)  NOT NULL,
              version       VARCHAR(32)  NOT NULL,
              description   VARCHAR(255),
              checksum      VARCHAR(128),
              installed_at  BIGINT       NOT NULL,
              execution_ms  BIGINT       NOT NULL,
              success       TINYINT(1)   NOT NULL,
              error_message TEXT,
              UNIQUE KEY uq_${table}_module_version (module_id, version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent()
    }

    companion object {
        private val TABLE_NAME_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]{0,62}$""")

        /**
         * History 表名只允许标识符字符,防 SQL 注入(表名走拼接不走参数绑定)。
         * 失败抛 [IllegalArgumentException],由 application 启动时早失败。
         */
        internal fun validateTableName(name: String): String {
            require(TABLE_NAME_REGEX.matches(name)) {
                "invalid migration history table name: '$name'; must match $TABLE_NAME_REGEX"
            }
            return name
        }
    }
}
