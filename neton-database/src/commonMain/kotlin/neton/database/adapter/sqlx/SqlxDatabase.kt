package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import neton.database.api.DbContext
import neton.database.api.DbSessionProvider
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.sql.Dialect
import neton.database.sql.MySqlDialect
import neton.database.sql.PostgresDialect
import neton.database.sql.SqliteDialect

/**
 * 持有 sqlx4k 数据源（PostgreSQL / MySQL / SQLite），根据 database.conf 配置自动选择驱动.
 *
 * **K/N executable 单 driver 约束 (NETON-DB-VARIANT, 2026-05-20)**:
 *   每个 sqlx4k native driver klib 携带完整 Rust runtime; 若 K/N executable 同时链接
 *   多个 driver, ld.lld 会报 duplicate symbol (rust_eh_personality 等). 所以
 *   neton-database 改成 **build-time variant**: gradle property `neton.database.driver`
 *   选 `postgres` | `mysql` | `sqlite` (默认 postgres), 编译期只链接一个 driver.
 *
 *   driver instantiation 通过 `createSqlxDriver(config)` 委托给 variant 源码目录
 *   ({postgres,mysql,sqlite}Variant/kotlin); 本对象不直接引用 PostgreSQL/MySQL/SQLite 类.
 *
 *   切换 variant: `./gradlew -Pneton.database.driver=sqlite <target>`
 */
object SqlxDatabase {
    private var db: QueryExecutor? = null
    private var closeDriver: (suspend () -> Unit)? = null
    private var driver: DatabaseDriver = DatabaseDriver.MEMORY
    private var sessions: SqlxSessionProvider? = null
    private var context: SqlxDbContext? = null

    fun initialize(config: DatabaseConfig): DbContext {
        check(db == null) {
            "Database is already initialized. Close it before initializing another driver."
        }
        driver = config.driver
        val handle = createSqlxDriver(config)
        val executor = handle.executor
        closeDriver = handle.close
        db = executor
        val provider = SqlxSessionProvider(executor, config.driver.toDialect())
        sessions = provider
        return SqlxDbContext(provider).also { context = it }
    }

    fun require(): QueryExecutor = db ?: throw IllegalStateException(
        "SqlxDatabase 未初始化，请先调用 database { } (or SqlxDatabase.initialize(config))"
    )

    fun currentDriver(): DatabaseDriver = driver

    internal fun requireContext(): DbContext = context
        ?: error("Database context is not initialized. Install database { } before using a Table.")

    internal fun requireSessionProvider(): DbSessionProvider = sessions
        ?: error("Database session provider is not initialized. Install database { } first.")

    /** 执行 DDL，由业务按需调用 */
    suspend fun executeDdl(ddl: String) {
        require().execute(ddl).getOrThrow()
    }

    suspend fun close() {
        closeDriver?.invoke()
        closeDriver = null
        db = null
        sessions = null
        context = null
        driver = DatabaseDriver.MEMORY
    }
}

internal data class SqlxDriverHandle(
    val executor: QueryExecutor,
    val close: suspend () -> Unit,
)

internal fun DatabaseDriver.toDialect(): Dialect = when (this) {
    DatabaseDriver.POSTGRESQL -> PostgresDialect
    DatabaseDriver.MYSQL -> MySqlDialect
    DatabaseDriver.SQLITE, DatabaseDriver.MEMORY -> SqliteDialect
}

// `createSqlxDriver(config)` 是 build variant 注入的工厂函数, 定义在
// src/{postgres,mysql,sqlite}Variant/kotlin/.../SqlxDriverFactory.kt.
// build.gradle.kts 按 gradle property `neton.database.driver` 添加对应的 srcDir,
// commonMain 编译时该函数可见.
