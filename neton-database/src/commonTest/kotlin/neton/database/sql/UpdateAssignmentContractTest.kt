package neton.database.sql

import neton.database.api.ColumnAssignment
import neton.database.dsl.ColumnRef
import neton.database.dsl.QueryAst
import neton.database.dsl.TableMeta
import neton.database.dsl.and
import neton.database.dsl.eq
import neton.database.dsl.ge
import neton.database.dsl.lt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UPDATE 的 SET 子句契约，重点是原子增减。
 *
 * 业务里「先查出来、改完再写回去」在并发下会丢更新，所以邀请码计数、余额扣减、
 * session_version 递增这类写法此前只能绕开 DSL 手写 SQL。`increment/decrement`
 * 把它们表达成 `col = col + ?`，配合 WHERE 就是一次 CAS：条件不成立时影响行数为 0。
 */
class UpdateAssignmentContractTest {

    private val builder = SqlBuilder(PostgresDialect)
    private val table = TableMeta("member_invite_codes")

    private fun ast(where: neton.database.dsl.Predicate? = null) =
        QueryAst<Any>(table = table, where = where)

    // ---- 字面量赋值（原有行为不能变）----

    @Test
    fun literalAssignmentBindsValueAsParameter() {
        val built = builder.buildUpdate(
            ast(ColumnRef("id") eq 7L),
            linkedMapOf("status" to ColumnAssignment.Literal(1)),
        )

        assertTrue(built.sql.contains("SET \"status\" = "), built.sql)
        // 值走绑定参数，不拼进 SQL
        assertTrue(built.args.contains(1))
        assertTrue(built.args.contains(7L))
    }

    // ---- 原子增减 ----

    @Test
    fun incrementRendersColumnPlusParameter() {
        val built = builder.buildUpdate(
            ast(ColumnRef("id") eq 7L),
            linkedMapOf("used_count" to ColumnAssignment.Delta(1)),
        )

        assertTrue(
            built.sql.contains("\"used_count\" = \"used_count\" + "),
            "增量必须由数据库计算，实际 SQL: ${built.sql}",
        )
        assertEquals(listOf<Any?>(1L, 7L), built.args, "delta 也要走绑定参数")
    }

    @Test
    fun decrementRendersNegativeDelta() {
        // decrement(x, 5) 在 scope 层折算为 Delta(-5)
        val built = builder.buildUpdate(
            ast(ColumnRef("id") eq 1L),
            linkedMapOf("balance" to ColumnAssignment.Delta(-500)),
        )

        assertTrue(built.sql.contains("\"balance\" = \"balance\" + "), built.sql)
        assertEquals(-500L, built.args.first())
    }

    @Test
    fun casKeepsGuardInWhereClause() {
        // 邀请码防超发：used_count = used_count + 1 WHERE id = ? AND used_count < ?
        val built = builder.buildUpdate(
            ast(and(ColumnRef("id") eq 7L, ColumnRef("used_count") lt 100)),
            linkedMapOf("used_count" to ColumnAssignment.Delta(1)),
        )

        assertTrue(built.sql.contains("\"used_count\" = \"used_count\" + "), built.sql)
        assertTrue(built.sql.contains("WHERE"), built.sql)
        assertTrue(built.sql.contains("\"used_count\" < "), built.sql)
        // 顺序：SET 的 delta 先绑定，随后是 WHERE 的参数
        assertEquals(listOf<Any?>(1L, 7L, 100), built.args)
    }

    @Test
    fun balanceDeductionGuardsAgainstOverdraft() {
        // balance = balance - amount WHERE id = ? AND balance >= amount
        val amount = 250L
        val built = builder.buildUpdate(
            ast(and(ColumnRef("id") eq 1L, ColumnRef("balance") ge amount)),
            linkedMapOf("balance" to ColumnAssignment.Delta(-amount)),
        )

        assertTrue(built.sql.contains("\"balance\" = \"balance\" + "), built.sql)
        assertTrue(built.sql.contains("\"balance\" >= "), built.sql)
        assertEquals(listOf<Any?>(-250L, 1L, 250L), built.args)
    }

    // ---- 混合与边界 ----

    @Test
    fun literalAndDeltaCanBeMixedInOneStatement() {
        val built = builder.buildUpdate(
            ast(ColumnRef("id") eq 1L),
            linkedMapOf(
                "used_count" to ColumnAssignment.Delta(1),
                "updated_at" to ColumnAssignment.Literal(1700000000L),
            ),
        )

        assertTrue(built.sql.contains("\"used_count\" = \"used_count\" + "), built.sql)
        assertTrue(built.sql.contains("\"updated_at\" = "), built.sql)
        assertEquals(listOf<Any?>(1L, 1700000000L, 1L), built.args)
    }

    @Test
    fun emptyAssignmentsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            builder.buildUpdate(ast(), linkedMapOf())
        }
    }

    @Test
    fun updateWithoutWhereHasNoWhereClause() {
        // 全表更新是合法的（调用方自己负责），但不能凭空产生 WHERE
        val built = builder.buildUpdate(
            ast(),
            linkedMapOf("session_version" to ColumnAssignment.Delta(1)),
        )
        assertTrue(!built.sql.contains("WHERE"), built.sql)
    }
}
