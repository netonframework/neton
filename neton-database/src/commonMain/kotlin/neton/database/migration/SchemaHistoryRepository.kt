package neton.database.migration

import neton.core.module.MigrationDialect
import neton.database.api.DbContext

/**
 * 全局 history 表的 DDL + CRUD,跨 module 共享 (SPEC §0.4 / §六)。
 *
 * # Frozen contract (DB-MIG-2)
 *
 * **Identity key**: `(module_id, version)` 组合键。同 module 内 version 唯一;跨 module
 * 版本号可以重复 (`payment.V001` 与 `member.V001` 互不冲突)。`module_id` 是业务声明的
 * **opaque namespace**,framework 不解释其语义,只用它做 history 隔离。
 *
 * **列契约** (三方言对齐;`success` 类型按方言原生表示,读出在 [parseSuccess] 兼容):
 *
 * | 列 | SQLite | PostgreSQL | MySQL |
 * |---|---|---|---|
 * | `module_id` | TEXT NOT NULL | VARCHAR(64) NOT NULL | VARCHAR(64) NOT NULL |
 * | `version` | TEXT NOT NULL | VARCHAR(32) NOT NULL | VARCHAR(32) NOT NULL |
 * | `description` | TEXT | VARCHAR(255) | VARCHAR(255) |
 * | `checksum` | TEXT | VARCHAR(128) | VARCHAR(128) |
 * | `installed_at` | INTEGER NOT NULL | BIGINT NOT NULL | BIGINT NOT NULL |
 * | `execution_ms` | INTEGER NOT NULL | BIGINT NOT NULL | BIGINT NOT NULL |
 * | `success` | INTEGER NOT NULL | BOOLEAN NOT NULL | TINYINT(1) NOT NULL |
 * | `error_message` | TEXT | TEXT | TEXT |
 * | UNIQUE | `UNIQUE(module_id, version)` | 同 | 同 (inline, 避免 named index 长度溢出) |
 *
 * 写入: SQL 拼接 (因表名是 identifier 无法参数绑定);值走 [esc] 单引号转义。表名经
 * [validateTableName] 静态校验 (允许字符 `[A-Za-z_][A-Za-z0-9_]{0,62}`)。
 *
 * # 操作员恢复路径 (success=false)
 *
 * 一旦某行 `success=false`,[MigrationEngine] 的所有后续 UP 都会 fail-fast Aborted
 * (SPEC §6.3 红线: 不自动重试)。操作员必须:
 *   1. 排查失败原因 (查 `error_message` + 数据库实际状态)
 *   2. 手工修复 schema (回滚已部分 commit 的 DDL,尤其 MySQL)
 *   3. 手工 `DELETE FROM <history_table> WHERE module_id=? AND version=?`
 *   4. 重新 `./application.kexe migrate up`
 *
 * 框架**不**提供 `migrate reset` / `migrate retry` 命令 — 这两个属于危险操作,留在 SQL
 * 层显式执行,避免误用。
 *
 * # 运行时约束
 *
 * 所有 SQL 走 [DbContext.execute] / [DbContext.fetchAll] (raw 逃生口),不依赖 sqlx4k
 * driver 类型 — engine 与 application serve 共享同一个 DbContext / driver
 * (NETON-DB-VARIANT 单 driver 约束)。
 */
internal class SchemaHistoryRepository(
    private val db: DbContext,
    private val config: MigrationConfig,
) {
    private val table: String = validateTableName(config.historyTable)
    private val dialect: MigrationDialect = config.dialect
    private var qualifiedTable: String = table

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
                "SELECT table_schema FROM information_schema.tables " +
                    "WHERE table_schema = current_schema() AND table_name='$table' LIMIT 1"
            MigrationDialect.MYSQL ->
                "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = '$table' LIMIT 1"
        }
        val rows = db.fetchAll(sql)
        if (dialect == MigrationDialect.POSTGRESQL && rows.isNotEmpty()) {
            qualifiedTable = qualifyPostgresTable(rows.first().string("table_schema"), table)
        }
        return rows.isNotEmpty()
    }

    /**
     * 确保 history 表存在并在返回前验证可访问。
     *
     * PostgreSQL / SQLite 的 DDL 与验证固定在同一 transaction session，提交后其它连接
     * 才允许继续读取，避免连接池切换造成冷启首建表暂不可见。MySQL DDL 会隐式提交，
     * 因此不包事务，但仍通过查询验证 execute 已真正完成。
     */
    suspend fun ensureExists() {
        when (dialect) {
            MigrationDialect.POSTGRESQL, MigrationDialect.SQLITE -> db.transaction {
                if (this@SchemaHistoryRepository.dialect == MigrationDialect.POSTGRESQL) {
                    val schema = fetchAll("SELECT current_schema() AS table_schema")
                        .first()
                        .string("table_schema")
                    qualifiedTable = qualifyPostgresTable(schema, table)
                }
                execute(ddl())
                verifyAccessible(this)
            }
            MigrationDialect.MYSQL -> {
                db.execute(ddl())
                verifyAccessible(db)
            }
        }
    }

    private suspend fun verifyAccessible(context: DbContext) {
        context.fetchAll("SELECT module_id FROM $qualifiedTable WHERE 1 = 0")
    }

    /** 列出全部 history 行,按 (module_id, version) 升序。 */
    suspend fun listAll(): List<Row> {
        val sql = """
            SELECT module_id, version, description, checksum,
                   installed_at, execution_ms, success, error_message
            FROM $qualifiedTable
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
        insert(db, row)
    }

    suspend fun insert(context: DbContext, row: Row) {
        val sql = """
            INSERT INTO $qualifiedTable
              (module_id, version, description, checksum, installed_at, execution_ms, success, error_message)
            VALUES
              ('${esc(row.moduleId)}', '${esc(row.version)}', '${esc(row.description)}', '${esc(row.checksum)}',
               ${row.installedAt}, ${row.executionMs},
               ${boolLit(row.success)},
               ${row.errorMessage?.let { "'${esc(it)}'" } ?: "NULL"})
        """.trimIndent()
        context.execute(sql)
    }

    private fun ddl(): String = historyTableDdl(dialect, qualifiedTable)

    private fun boolLit(v: Boolean): String = historyBoolLiteral(dialect, v)

    private fun esc(s: String): String = escapeSqlString(s)

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

// ============================================================
// History contract — DDL / boolean literal / single-quote escape.
//
// 把这些抽出为 top-level internal 函数,让 tests 能 lock 死 contract (DDL golden tests).
// 不在 SchemaHistoryRepository 内是因为 instance 方法不便测 — 测试要构造 DbContext.
// ============================================================

internal fun historyTableDdl(dialect: MigrationDialect, table: String): String = when (dialect) {
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

    // MySQL 用行内 UNIQUE (跟 PG / SQLite 一致), 避免 named index `uq_${table}_...`
    // 在 table 名接近 validateTableName 上限(63 char)时超过 MySQL 64 char 标识符限制.
    // utf8mb4_bin: history 表只存 ASCII (module_id / version / checksum), 二进制对比,
    // 避免不同 collation 下大小写敏感性漂移影响 UNIQUE 行为.
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
          UNIQUE (module_id, version)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
    """.trimIndent()
}

/** Boolean → SQL literal,方言原生表示。frozen DB-MIG-2。 */
internal fun historyBoolLiteral(dialect: MigrationDialect, v: Boolean): String = when (dialect) {
    MigrationDialect.POSTGRESQL -> if (v) "TRUE" else "FALSE"
    MigrationDialect.MYSQL, MigrationDialect.SQLITE -> if (v) "1" else "0"
}

internal fun escapeSqlString(s: String): String = s.replace("'", "''")

private fun qualifyPostgresTable(schema: String, table: String): String =
    "${quotePostgresIdentifier(schema)}.${quotePostgresIdentifier(table)}"

private fun quotePostgresIdentifier(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""
