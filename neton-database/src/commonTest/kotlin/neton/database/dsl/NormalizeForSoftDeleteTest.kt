package neton.database.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NormalizeForSoftDeleteTest {

    private val deletedCol = ColumnRef("deleted")
    private val table = TableMeta("users")

    @Test
    fun whenIncludeDeleted_true_returnsSameAst() {
        val ast = QueryAst<Any>(table = table, where = ColumnRef("id") eq 1L, includeDeleted = true)
        val out = ast.normalizeForSoftDelete(deletedCol)
        assertSame(ast, out)
    }

    @Test
    fun whenDeletedColumnNull_returnsSameAst() {
        val ast = QueryAst<Any>(table = table, where = ColumnRef("id") eq 1L, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(null)
        assertSame(ast, out)
    }

    @Test
    fun whenIncludeDeletedFalse_injectsDeletedEqFalse() {
        val ast = QueryAst<Any>(table = table, where = null, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(deletedCol)
        assertEquals(Predicate.Eq(deletedCol, false), out.where)
    }

    @Test
    fun whenIncludeDeletedFalse_andExistingWhere_combinesWithAnd() {
        val idEq = ColumnRef("id") eq 1L
        val ast = QueryAst<Any>(table = table, where = idEq, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(deletedCol)
        val combined = out.where as? Predicate.And
        assertEquals(2, combined?.children?.size)
        assertEquals(idEq, combined?.children?.get(0))
        assertEquals(Predicate.Eq(deletedCol, false), combined?.children?.get(1))
    }
}
