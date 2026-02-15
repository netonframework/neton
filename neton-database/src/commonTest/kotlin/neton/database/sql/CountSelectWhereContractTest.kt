package neton.database.sql

import neton.database.dsl.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约：同一 QueryAst 下 buildCount 与 buildSelect 的 WHERE 及对应参数一致，
 * 保证「同 where 下 page().total == count()」在实现层成立。
 */
class CountSelectWhereContractTest {

    private val dialect = PostgresDialect
    private val builder = SqlBuilder(dialect)
    private val table = TableMeta("users")

    @Test
    fun sameWhere_sameWhereClauseAndArgs() {
        val where = ColumnRef("status") eq "active"
        val ast = QueryAst<Any>(
            table = table,
            where = where,
            orderBy = listOf(ColumnRef("id").asc()),
            limit = 10,
            offset = 0
        )
        val countSql = builder.buildCount(ast)
        val selectSql = builder.buildSelect(ast)

        assertTrue(countSql.sql.contains("WHERE"))
        assertTrue(selectSql.sql.contains("WHERE"))
        val countWhere = countSql.sql.substringAfter("WHERE ").substringBefore(" ORDER BY").ifEmpty { countSql.sql.substringAfter("WHERE ") }
        val selectWhere = selectSql.sql.substringAfter("WHERE ").substringBefore(" ORDER BY").ifEmpty { selectSql.sql.substringAfter("WHERE ") }
        assertEquals(countWhere, selectWhere)

        assertEquals(countSql.args, selectSql.args.take(countSql.args.size))
    }

    @Test
    fun noWhere_bothHaveNoWhereClause() {
        val ast = QueryAst<Any>(table = table, limit = 5, offset = 0)
        val countSql = builder.buildCount(ast)
        val selectSql = builder.buildSelect(ast)

        assertEquals("SELECT COUNT(*) FROM \"users\"", countSql.sql)
        assertEquals(0, countSql.args.size)

        assertTrue(selectSql.sql.startsWith("SELECT * FROM \"users\""))
        assertEquals(2, selectSql.args.size)
        assertEquals(5, selectSql.args[0])
        assertEquals(0, selectSql.args[1])
    }
}
