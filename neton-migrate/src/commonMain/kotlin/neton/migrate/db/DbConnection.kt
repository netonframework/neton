package neton.migrate.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.mysql.MySQL
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite
import neton.migrate.config.Driver
import neton.migrate.config.MigrateConfig

/**
 * 直接持有 sqlx4k 的 QueryExecutor —— 不依赖 neton-database。
 *
 * URI 解析比 neton-database 更宽松：直接把用户给的 uri 透传给 sqlx4k driver。
 *   - sqlite: "sqlite::memory:" / "sqlite:///path/to/db" / "/path/to/db"
 *   - postgresql: "postgresql://user:pass@host:port/db"
 *   - mysql: "mysql://user:pass@host:port/db"
 */
class DbConnection private constructor(
    private val executor: QueryExecutor,
    val driver: Driver
) {

    suspend fun execute(sql: String): Long =
        executor.execute(Statement.create(sql)).getOrThrow()

    /**
     * 把多条语句包在同一个 sqlx4k Transaction 内 (pinned connection) 执行.
     *
     * 背景 (v1 BLOCKER-3 修复):
     *   原 [UpCommand.executeScript] 通过 [execute] 分别发 "BEGIN" / 业务 statements
     *   / "COMMIT". sqlx4k 的 PG driver 使用连接池, 每次 [execute] 可能从池里取
     *   不同连接 — BEGIN 在 conn-A 开了事务但 ALTER 跑在 conn-B 上是 autocommit;
     *   随后业务调用 hang 在池耗尽 (conn-A 还在 tx 模式占用). 表象就是 V007/V008
     *   "OK 但 schema 没变" + history 没写, 或新 multi-statement migration hang.
     *
     * 本方法用 sqlx4k 的 `QueryExecutor.Transactional` API: `.transaction { tx -> ... }`
     * 拿到 pinned 连接, 块内所有 `tx.execute` 都走同一连接; 块正常返回自动 commit,
     * 抛异常自动 rollback.
     *
     * driver 不支持 (e.g. MySQL DDL autocommit) -> 抛 [UnsupportedOperationException].
     * 调用方按 driver 类型决定是否调本方法.
     */
    suspend fun executeAllInTransaction(statements: List<String>) {
        val tx = executor as? io.github.smyrgeorge.sqlx4k.QueryExecutor.Transactional
            ?: throw UnsupportedOperationException(
                "driver $driver does not support transactions via sqlx4k Transactional API"
            )
        tx.transaction {
            for (stmt in statements) {
                execute(Statement.create(stmt)).getOrThrow()
            }
        }
    }

    /**
     * MySQL 路径: 不开事务 (DDL 自动 commit), 逐条 [execute] 同 v0.1 行为.
     * 任一条失败抛, 调用方负责 history 标记 success=false.
     */
    suspend fun executeAllSequential(statements: List<String>) {
        for (stmt in statements) {
            execute(stmt)
        }
    }

    /**
     * 按列名读字符串。sqlx4k 把所有列以字符串形式返回，由调用方转型。
     * 仅供 history 表读取使用 —— 列结构固定。
     */
    suspend fun queryRows(sql: String, columns: List<String>): List<Map<String, String?>> {
        val rows = executor.fetchAll(Statement.create(sql)).getOrThrow()
        return rows.map { row ->
            val m = mutableMapOf<String, String?>()
            for (col in columns) {
                m[col] = row.get(col).asStringOrNull()
            }
            m.toMap()
        }
    }

    suspend fun close() {
        // sqlx4k QueryExecutor 没有显式 close API；进程退出时由 driver 释放
    }

    companion object {
        suspend fun connect(config: MigrateConfig): Result<DbConnection> = try {
            // SQLite 提前检查文件路径父目录是否存在 — sqlx4k 在父目录不存在时会 Rust panic
            if (config.driver == Driver.SQLITE) {
                val pathPart = config.uri
                    .removePrefix("sqlite://")
                    .removePrefix("sqlite:")
                    .takeIf { it.isNotEmpty() && it != ":memory:" }
                if (pathPart != null) {
                    val lastSlash = pathPart.lastIndexOf('/')
                    if (lastSlash > 0) {
                        val parentDir = pathPart.substring(0, lastSlash)
                        if (!neton.migrate.io.FileIO.isDirectory(parentDir)) {
                            return Result.failure(IllegalArgumentException("sqlite parent directory does not exist: $parentDir"))
                        }
                    }
                }
            }

            val executor: QueryExecutor = when (config.driver) {
                Driver.SQLITE -> SQLite(url = normalizeSqliteUri(config.uri))
                Driver.POSTGRESQL -> {
                    val parsed = parseUri(config.uri, "postgresql")
                    PostgreSQL(
                        url = "postgresql://${parsed.host}:${parsed.port}/${parsed.database}",
                        username = parsed.user,
                        password = parsed.password
                    )
                }
                Driver.MYSQL -> {
                    val parsed = parseUri(config.uri, "mysql")
                    MySQL(
                        url = "mysql://${parsed.host}:${parsed.port}/${parsed.database}",
                        username = parsed.user,
                        password = parsed.password
                    )
                }
            }
            // 探活
            executor.execute(Statement.create(probeSql(config.driver))).getOrThrow()
            Result.success(DbConnection(executor, config.driver))
        } catch (e: Throwable) {
            Result.failure(e)
        }

        private fun probeSql(driver: Driver) = when (driver) {
            Driver.SQLITE -> "SELECT 1"
            Driver.POSTGRESQL -> "SELECT 1"
            Driver.MYSQL -> "SELECT 1"
        }

        private fun normalizeSqliteUri(uri: String): String = when {
            uri.startsWith("sqlite:") -> uri
            uri == ":memory:" -> "sqlite::memory:"
            else -> "sqlite://$uri"
        }

        private fun parseUri(uri: String, scheme: String): ParsedJdbc {
            // 简易解析：scheme://[user[:password]@]host[:port]/database
            val schemePrefix = "$scheme://"
            val withoutScheme = if (uri.startsWith(schemePrefix)) uri.removePrefix(schemePrefix) else uri
            val atIdx = withoutScheme.indexOf('@')
            val (auth, hostPart) = if (atIdx >= 0) {
                withoutScheme.substring(0, atIdx) to withoutScheme.substring(atIdx + 1)
            } else {
                "" to withoutScheme
            }
            val user: String
            val password: String
            if (auth.isEmpty()) {
                user = ""
                password = ""
            } else {
                val colon = auth.indexOf(':')
                if (colon >= 0) {
                    user = auth.substring(0, colon)
                    password = auth.substring(colon + 1)
                } else {
                    user = auth
                    password = ""
                }
            }
            val slashIdx = hostPart.indexOf('/')
            val hostPort = if (slashIdx >= 0) hostPart.substring(0, slashIdx) else hostPart
            val database = if (slashIdx >= 0) hostPart.substring(slashIdx + 1) else ""
            val colonIdx = hostPort.indexOf(':')
            val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
            val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: defaultPort(scheme) else defaultPort(scheme)
            return ParsedJdbc(host, port, user, password, database)
        }

        private fun defaultPort(scheme: String): Int = when (scheme) {
            "postgresql" -> 5432
            "mysql" -> 3306
            else -> 0
        }

        private data class ParsedJdbc(
            val host: String,
            val port: Int,
            val user: String,
            val password: String,
            val database: String
        )
    }
}
