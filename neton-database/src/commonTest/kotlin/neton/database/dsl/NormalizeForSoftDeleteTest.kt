package neton.database.dsl

import neton.database.api.SoftDeleteConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NormalizeForSoftDeleteTest {

    private val deletedCol = ColumnRef("deleted")
    private val boolConfig = SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = false)
    private val intConfig = SoftDeleteConfig(deletedColumn = "deleted", notDeletedValue = 0)
    private val table = TableMeta("users")

    @Test
    fun whenIncludeDeleted_true_returnsSameAst() {
        val ast = QueryAst<Any>(table = table, where = ColumnRef("id") eq 1L, includeDeleted = true)
        val out = ast.normalizeForSoftDelete(boolConfig)
        assertSame(ast, out)
    }

    @Test
    fun whenConfigNull_returnsSameAst() {
        val ast = QueryAst<Any>(table = table, where = ColumnRef("id") eq 1L, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(null)
        assertSame(ast, out)
    }

    @Test
    fun whenBoolField_injectsDeletedEqFalse() {
        val ast = QueryAst<Any>(table = table, where = null, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(boolConfig)
        assertEquals(Predicate.Eq(deletedCol, false), out.where)
    }

    @Test
    fun whenIntField_injectsDeletedEqZero() {
        val ast = QueryAst<Any>(table = table, where = null, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(intConfig)
        assertEquals(Predicate.Eq(deletedCol, 0), out.where)
    }

    @Test
    fun whenIncludeDeletedFalse_andExistingWhere_combinesWithAnd() {
        val idEq = ColumnRef("id") eq 1L
        val ast = QueryAst<Any>(table = table, where = idEq, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(boolConfig)
        val combined = out.where as? Predicate.And
        assertEquals(2, combined?.children?.size)
        assertEquals(idEq, combined?.children?.get(0))
        assertEquals(Predicate.Eq(deletedCol, false), combined?.children?.get(1))
    }

    @Test
    fun whenIntField_andExistingWhere_combinesWithAnd() {
        val idEq = ColumnRef("id") eq 1L
        val ast = QueryAst<Any>(table = table, where = idEq, includeDeleted = false)
        val out = ast.normalizeForSoftDelete(intConfig)
        val combined = out.where as? Predicate.And
        assertEquals(2, combined?.children?.size)
        assertEquals(idEq, combined?.children?.get(0))
        assertEquals(Predicate.Eq(deletedCol, 0), combined?.children?.get(1))
    }
}
