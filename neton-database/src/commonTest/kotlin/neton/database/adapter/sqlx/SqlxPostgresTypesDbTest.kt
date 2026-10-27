package neton.database.adapter.sqlx

import kotlinx.coroutines.runBlocking
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.dbContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PostgreSQL 列类型解码回归（真实数据库，设置 `NETON_DB_TEST_URI` 时运行，否则跳过）。
 *
 * sqlx4k 1.12 遇到 `numeric` 列会在原生层 panic（NulError），把整个进程带走 ——
 * `SUM(bigint)`、`AVG(...)`、`ROUND(...)` 的结果都是 numeric。这里锁住「numeric 能按字符串 / 数字读回」，
 * 依赖升级时第一时间发现回退。
 */
class SqlxPostgresTypesDbTest {

    private val uri: String? = postgresTestUri()

    @Test
    fun numericColumnsDecodeWithoutCrashing() {
        val dbUri = uri ?: return
        SqlxDatabase.initialize(DatabaseConfig(driver = DatabaseDriver.POSTGRESQL, uri = dbUri))
        try {
            runBlocking {
                val db = dbContext()
                val row = db.fetchAll(
                    "SELECT 1.5::numeric AS n, SUM(x) AS total, AVG(x) AS mean FROM (VALUES (1::bigint), (2::bigint), (4::bigint)) AS t(x)",
                ).single()
                assertEquals(1.5, row.string("n").toDouble())
                assertEquals(7L, row.string("total").toLong())
                assertEquals(2.3333, row.string("mean").toDouble(), 0.001)
            }
        } finally {
            runBlocking { SqlxDatabase.close() }
        }
    }
}
