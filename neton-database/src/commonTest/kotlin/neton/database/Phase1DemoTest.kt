package neton.database

import neton.database.dsl.*
import neton.database.sql.SqlBuilder
import neton.database.sql.PostgresDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 Demo 闭环：many(ids)、whenPresent 系列、分页与 count 同源。
 * 不依赖真实 DB，仅验证 DSL + SqlBuilder 行为。
 */
class Phase1DemoTest {

    private val builder = SqlBuilder(PostgresDialect)
    private val table = TableMeta("users")
    private val idCol = ColumnRef("id")
    private val nameCol = ColumnRef("name")
    private val statusCol = ColumnRef("status")

    @Test
    fun manyIds_empty_producesOneEqZero() {
        val ast = QueryAst<Any>(
            table = table,
            where = idCol `in` emptyList<Any?>(),
            includeDeleted = false
        )
        val built = builder.buildSelect(ast)
        assertTrue(built.sql.contains("1 = 0"), "IN 空集应生成 1=0，实际: ${built.sql}")
        assertEquals(0, built.args.size)
    }

    @Test
    fun manyIds_nonEmpty_producesInClause() {
        val ids = listOf(1L, 2L).map { it as Any? }
        val ast = QueryAst<Any>(table = table, where = idCol `in` ids, includeDeleted = false)
        val built = builder.buildSelect(ast)
        assertTrue(built.sql.contains("IN ("), "应生成 IN 子句，实际: ${built.sql}")
        assertEquals(2, built.args.size)
        assertEquals(1L, built.args[0])
        assertEquals(2L, built.args[1])
    }

    @Test
    fun whenPresent_null_returnsTrue() {
        val scope = PredicateScope()
        val p = scope.whenPresent<String>(null) { nameCol like "%$it%" }
        assertTrue(p is Predicate.True)
    }

    @Test
    fun whenPresent_value_returnsPredicate() {
        val scope = PredicateScope()
        val p = scope.whenPresent("ok") { nameCol like "%$it%" }
        assertNotNull(p)
        assertTrue(p is Predicate.Like)
        assertEquals("%ok%", (p as Predicate.Like).value)
    }

    @Test
    fun whenNotBlank_blank_returnsTrue() {
        val scope = PredicateScope()
        val p = scope.whenNotBlank("  ") { nameCol like "%$it%" }
        assertTrue(p is Predicate.True)
    }

    @Test
    fun whenNotEmpty_empty_returnsTrue() {
        val scope = PredicateScope()
        val p = scope.whenNotEmpty(emptyList<Long>()) { idCol `in` it.map { v -> v as Any? } }
        assertTrue(p is Predicate.True)
    }

    @Test
    fun listPage_countAndSelectShareWhere() {
        val scope = PredicateScope()
        val where = scope.and(statusCol eq 1, scope.whenPresent("tom") { nameCol like "%$it%" })
        val ast = QueryAst<Any>(
            table = table,
            where = where,
            orderBy = listOf(idCol.desc()),
            limit = 20,
            offset = 0
        )
        val countSql = builder.buildCount(ast)
        val selectSql = builder.buildSelect(ast)
        val countWhere = countSql.sql.substringAfter("WHERE ")
        val selectWhere = selectSql.sql.substringAfter("WHERE ").substringBefore(" ORDER BY")
        assertEquals(countWhere, selectWhere)
        assertEquals(countSql.args, selectSql.args.take(countSql.args.size))
    }
}
