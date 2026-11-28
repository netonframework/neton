package neton.saga

import neton.database.api.DbContext
import neton.database.sql.PostgresDialect

sealed interface CompensationClaim {
    class Acquired internal constructor(val id: String, val generation: Int) : CompensationClaim
    enum class Unavailable : CompensationClaim { BUSY, HANDED_OFF, NEEDS_ATTENTION }
}

enum class CompensationFinish { HANDED_OFF, RETRY_READY, NEEDS_ATTENTION, FENCED }

/**
 * PostgreSQL authority for a single durable compensation command. [id] parameters are stable,
 * scope-qualified command identities; the generation grants write authority, never a new wire identity.
 * Callbacks run inside the same database transaction and must not perform external I/O.
 */
class CompensationStore(private val db: DbContext) {
    init { require(db.dialect is PostgresDialect) }

    /** Register in the transaction that enqueues the immutable compensation command. */
    suspend fun register(id: String, maxAttempts: Int) {
        check(db.inTransaction())
        require(id.isNotBlank() && maxAttempts > 0)
        check(db.execute("INSERT INTO ns_compensation(id,max_attempts) VALUES(:id,:max)",
            mapOf("id" to id, "max" to maxAttempts)) == 1L)
    }

    suspend fun claim(id: String, leaseMillis: Int, onAttention: suspend DbContext.() -> Unit): CompensationClaim {
        require(leaseMillis > 0)
        check(!db.inTransaction())
        return db.transaction {
            val row = lock(id)
            when (row.state) {
                "HANDED_OFF" -> return@transaction CompensationClaim.Unavailable.HANDED_OFF
                "NEEDS_ATTENTION" -> return@transaction CompensationClaim.Unavailable.NEEDS_ATTENTION
                "LEASED" -> if (unexpired(id)) return@transaction CompensationClaim.Unavailable.BUSY
            }
            if (row.generation == row.max) {
                attention(id, row.generation, "LEASE_EXPIRED", onAttention)
                return@transaction CompensationClaim.Unavailable.NEEDS_ATTENTION
            }
            check(execute("""
                UPDATE ns_compensation SET state='LEASED', generation=generation+1,
                    lease_until=clock_timestamp()+CAST(:ms AS bigint)*interval '1 millisecond' WHERE id=:id
            """.trimIndent(), mapOf("id" to id, "ms" to leaseMillis)) == 1L)
            CompensationClaim.Acquired(id, row.generation + 1)
        }
    }

    /** Record a matched durable receipt, or report uncertainty without inventing a business result. */
    suspend fun finish(
        claim: CompensationClaim.Acquired,
        confirmed: Boolean,
        onHandoff: suspend DbContext.() -> Unit,
        onAttention: suspend DbContext.() -> Unit,
    ): CompensationFinish {
        check(!db.inTransaction())
        return db.transaction {
            val id = claim.id
            val row = lock(id)
            if (row.state != "LEASED" || row.generation != claim.generation || !unexpired(id)) {
                return@transaction CompensationFinish.FENCED
            }
            when {
                confirmed -> {
                    onHandoff()
                    setState(id, "HANDED_OFF")
                    CompensationFinish.HANDED_OFF
                }
                row.generation == row.max -> {
                    attention(id, row.generation, "DELIVERY_UNCONFIRMED", onAttention)
                    CompensationFinish.NEEDS_ATTENTION
                }
                else -> {
                    setState(id, "READY")
                    CompensationFinish.RETRY_READY
                }
            }
        }
    }

    private data class Row(val state: String, val generation: Int, val max: Int)

    private suspend fun DbContext.lock(id: String): Row =
        fetchAll("SELECT state,generation,max_attempts FROM ns_compensation WHERE id=:id FOR UPDATE",
            mapOf("id" to id)).single().let { Row(it.string("state"), it.int("generation"), it.int("max_attempts")) }

    // Evaluate the lease against database time only after acquiring the row lock.
    private suspend fun DbContext.unexpired(id: String): Boolean =
        fetchAll("SELECT 1 FROM ns_compensation WHERE id=:id AND lease_until > clock_timestamp()",
            mapOf("id" to id)).isNotEmpty()

    private suspend fun DbContext.setState(id: String, state: String) {
        check(execute("UPDATE ns_compensation SET state=:state,lease_until=NULL WHERE id=:id",
            mapOf("id" to id, "state" to state)) == 1L)
    }

    private suspend fun DbContext.attention(id: String, generation: Int, reason: String, effect: suspend DbContext.() -> Unit) {
        effect()
        setState(id, "NEEDS_ATTENTION")
        check(execute("INSERT INTO ns_compensation_audit(id,generation,event,reason) VALUES(:id,:gen,'NEEDS_ATTENTION',:reason)",
            mapOf("id" to id, "gen" to generation, "reason" to reason)) == 1L)
    }
}
