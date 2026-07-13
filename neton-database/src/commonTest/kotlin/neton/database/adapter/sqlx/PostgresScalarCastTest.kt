package neton.database.adapter.sqlx

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresScalarCastTest {
    @Test
    fun castsScalarNamedParameters() {
        val sql = applyPostgresScalarCasts(
            "INSERT INTO t (a, b, c, d, e, f) VALUES (:a, :b, :c, :d, :e, :f)",
            mapOf(
                "a" to 1L,
                "b" to 2,
                "c" to 3.toShort(),
                "d" to 4.5,
                "e" to 6.5f,
                "f" to true,
            ),
        )

        assertEquals(
            "INSERT INTO t (a, b, c, d, e, f) VALUES (:a::int8, :b::int4, :c::int2, :d::float8, :e::float4, :f::boolean)",
            sql,
        )
    }

    @Test
    fun leavesCollectionsAndExistingCastsUnchanged() {
        val sql = applyPostgresScalarCasts(
            "SELECT * FROM t WHERE id IN (:ids) AND created_at > :ts::int8 AND status = :status",
            mapOf("ids" to listOf(1L, 2L), "ts" to 10L, "status" to 1),
        )

        assertEquals(
            "SELECT * FROM t WHERE id IN (:ids) AND created_at > :ts::int8 AND status = :status::int4",
            sql,
        )
    }

    @Test
    fun ignoresQuotedTextCommentsAndDollarQuotes() {
        val sql = applyPostgresScalarCasts(
            "SELECT ':id', \"col:id\", $$:id$$, :id -- :id\n/* :id */",
            mapOf("id" to 1L),
        )

        assertEquals("SELECT ':id', \"col:id\", $$:id$$, :id::int8 -- :id\n/* :id */", sql)
    }

    @Test
    fun preservesCastsInNativePostgresQuery() {
        val renderedSql = applyPostgresScalarCasts(
            "INSERT INTO t (created_at, count, ok) VALUES (:created_at, :count, :ok)",
            mapOf("created_at" to 1L, "count" to 2, "ok" to true),
        )

        val query = Statement.create(renderedSql)
            .bind("created_at", 1L)
            .bind("count", 2)
            .bind("ok", true)
            .renderNativeQuery(Dialect.PostgreSQL, ValueEncoderRegistry())

        assertEquals(
            "INSERT INTO t (created_at, count, ok) VALUES ($1::int8, $2::int4, $3::boolean)",
            query.sql,
        )
    }
}
