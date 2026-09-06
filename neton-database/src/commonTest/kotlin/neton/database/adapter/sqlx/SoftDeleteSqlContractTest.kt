package neton.database.adapter.sqlx

import kotlinx.coroutines.runBlocking
import neton.database.api.DbContext
import neton.database.api.EntityMapper
import neton.database.api.EntityMeta
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.SoftDeleteConfig
import neton.database.api.Table
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql
import neton.database.sql.Dialect
import neton.database.sql.PostgresDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 软删生成的 SQL 契约测试。
 *
 * 为什么必须有这一层：[SoftDeleteConfig.deletedAtColumn] 曾默认 `"deleted_at"`，而唯一的生成方
 * KSP 从不传它 —— 于是 destroy() 恒拼出 `SET deleted=..., deleted_at=...`，对只有 `deleted` 列的
 * 表被驱动打回 `[42703] column "deleted_at" of relation ... does not exist`。所有 @SoftDelete
 * 实体的删除路径都是坏的，而框架测试里没有任何一条断言过软删生成的 SQL：Phase1DemoTest 只测
 * DSL 谓词构造，NormalizeForSoftDeleteTest 只测谓词归一化，TableUserContractTest 的 stub 直接
 * `destroy() = false`。所以它一直藏到下游产品真的执行删除才暴露。
 *
 * 这里给 adapter 注入一个记录 SQL 的假 [DbContext]，直接读语句文本，不需要真库。
 * 生成器那一端由 neton-ksp 的 EntityTableSoftDeleteConfigTest 守：两边各锁一半，
 * 只锁 adapter 的话，「生成器硬编码一个 deleted_at」这条同样能致祸的回归会从另一端溜进来。
 */
class SoftDeleteSqlContractTest {

    private data class Widget(val id: Long, val deleted: Int)

    /** 只记录 SQL 与参数。软删路径用不到的一律 error，免得测试悄悄走了别的分支还以为绿了。 */
    private class RecordingDb : DbContext {
        val sqls = mutableListOf<String>()
        val params = mutableListOf<Map<String, Any?>>()

        override val dialect: Dialect = PostgresDialect
        override val interceptors: List<QueryInterceptor> = emptyList()

        override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
            sqls += sql
            this.params += params
            return 1L
        }

        override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
            sqls += sql
            this.params += params
            return emptyList()
        }

        override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> =
            error("RecordingDb does not support DSL queries")

        override suspend fun query(built: BuiltSql): List<Row> =
            error("RecordingDb does not support DSL queries")

        override suspend fun executeBuilt(built: BuiltSql): Long =
            error("RecordingDb does not support DSL queries")

        override suspend fun inTransaction(): Boolean = false

        override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = block()
    }

    private val meta = object : EntityMeta<Widget> {
        override val table = "widgets"
        override val idColumn = "id"
        override val columns = listOf("id", "deleted")
    }

    private fun table(db: DbContext, config: SoftDeleteConfig) = SqlxTableAdapter<Widget, Long>(
        meta = meta,
        dbProvider = { db },
        mapper = EntityMapper { Widget(it.long("id"), it.int("deleted")) },
        toParams = { mapOf("id" to it.id, "deleted" to it.deleted) },
        softDeleteConfig = config,
    )

    @Test
    fun configDoesNotAssumeATimestampColumnByDefault() {
        // 默认值就是这次的事故点：非 null 的默认列名 = 要求每张软删表都长一个通常并不存在的列。
        assertNull(SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0).deletedAtColumn)
    }

    @Test
    fun destroyWithoutTimestampColumnDoesNotMentionDeletedAt() = runBlocking {
        val db = RecordingDb()
        // 这份配置与 KSP 对「只有 deleted 列」的实体生成的逐字一致
        val ok = table(db, SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0)).destroy(7L)

        assertTrue(ok, "affected=1 时应报告删除成功")
        assertEquals("UPDATE widgets SET deleted = :deleted WHERE id = :id", db.sqls.single())
        assertFalse(
            db.sqls.single().contains("deleted_at"),
            "不得拼出实体未声明的时间戳列：${db.sqls.single()}",
        )
        // deleted 参数必须是「已删」值：notDeletedValue=0 时要翻成 1，写反了等于把行标成未删
        assertEquals(1, db.params.single()["deleted"])
        assertFalse(db.params.single().containsKey("deletedAt"))
    }

    @Test
    fun destroyManyWithoutTimestampColumnDoesNotMentionDeletedAt() = runBlocking {
        val db = RecordingDb()
        val n = table(db, SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0))
            .destroyMany(listOf(7L, 8L))

        assertEquals(1, n)
        assertEquals("UPDATE widgets SET deleted = :deleted WHERE id IN (:id0, :id1)", db.sqls.single())
        assertFalse(
            db.sqls.single().contains("deleted_at"),
            "不得拼出实体未声明的时间戳列：${db.sqls.single()}",
        )
    }

    @Test
    fun destroyWithDeclaredTimestampColumnStillFillsIt() = runBlocking {
        // 反向守卫：修复不是把这个能力删掉。实体真声明了 deletedAt 时仍要填 epoch millis ——
        // 少填不会报错，只会让「软删时间」静默变成 null，从响应码上完全看不出来。
        val db = RecordingDb()
        val ok = table(
            db,
            SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0, deletedAtColumn = "deleted_at"),
        ).destroy(7L)

        assertTrue(ok)
        assertEquals(
            "UPDATE widgets SET deleted = :deleted, deleted_at = :deletedAt WHERE id = :id",
            db.sqls.single(),
        )
        val stamped = db.params.single()["deletedAt"]
        assertTrue(stamped is Long && stamped > 0L, "deletedAt 应为 epoch millis，实际 $stamped")
    }
}
