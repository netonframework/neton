package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver

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
    private var driver: DatabaseDriver = DatabaseDriver.MEMORY

    fun initialize(config: DatabaseConfig) {
        driver = config.driver
        db = createSqlxDriver(config)
    }

    fun require(): QueryExecutor = db ?: throw IllegalStateException(
        "SqlxDatabase 未初始化，请先调用 database { } (or SqlxDatabase.initialize(config))"
    )

    fun currentDriver(): DatabaseDriver = driver

    /** 执行 DDL，由业务按需调用 */
    suspend fun executeDdl(ddl: String) {
        require().execute(ddl).getOrThrow()
    }
}

// `createSqlxDriver(config)` 是 build variant 注入的工厂函数, 定义在
// src/{postgres,mysql,sqlite}Variant/kotlin/.../SqlxDriverFactory.kt.
// build.gradle.kts 按 gradle property `neton.database.driver` 添加对应的 srcDir,
// commonMain 编译时该函数可见.
