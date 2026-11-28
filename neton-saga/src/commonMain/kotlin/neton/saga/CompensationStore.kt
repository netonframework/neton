package neton.saga

import neton.database.api.DbContext
import neton.database.sql.PostgresDialect

sealed interface CompensationClaim {
    class Acquired internal constructor(val id: String, val generation: Int) : CompensationClaim
    enum class Unavailable : CompensationClaim { BUSY, HANDED_OFF, NEEDS_ATTENTION }
}

enum class CompensationFinish { HANDED_OFF, RETRY_READY, NEEDS_ATTENTION, FENCED }
enum class CompensationRecovery { RESUMED, REPLAYED, CONFLICT, FORBIDDEN }

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

    suspend fun renew(claim: CompensationClaim.Acquired, leaseMillis: Int): Boolean {
        require(leaseMillis > 0)
        check(!db.inTransaction())
        return db.transaction {
            val row = lock(claim.id)
            if (row.state != "LEASED" || row.generation != claim.generation) return@transaction false
            execute("""
                UPDATE ns_compensation SET lease_until=GREATEST(lease_until,clock_timestamp()+CAST(:ms AS bigint)*interval '1 millisecond')
                WHERE id=:id AND lease_until > clock_timestamp()
            """.trimIndent(), mapOf("id" to claim.id, "ms" to leaseMillis)) == 1L
        }
    }

    /**
     * Authorize with a trusted application principal and evidence policy, before reading command state.
     * A successful restore adds exactly one attempt to the same command. The workflow CAS callback
     * must make no changes on false; it and the audit commit with enabling dispatch.
     */
    suspend fun recover(id: String, requestId: String, generation: Int, version: Int, actor: String, evidence: String,
        authorize: suspend (String, String, String) -> Boolean,
        restoreCheckpoint: suspend DbContext.() -> Boolean,
    ): CompensationRecovery {
        check(!db.inTransaction())
        if (!authorize(actor, id, evidence)) return CompensationRecovery.FORBIDDEN
        require(requestId.length in 1..128 && actor.length in 1..128 && evidence.length in 1..1024)
        return db.transaction {
            val row = lockOrNull(id) ?: return@transaction CompensationRecovery.CONFLICT
            val key = mapOf("id" to id, "request" to requestId)
            val prior = fetchAll("""
                SELECT generation,workflow_version,actor,evidence FROM ns_compensation_recovery
                WHERE id=:id AND request_id=:request
            """.trimIndent(), key).singleOrNull()
            if (prior != null) return@transaction if (prior.int("generation") == generation &&
                prior.int("workflow_version") == version && prior.string("actor") == actor && prior.string("evidence") == evidence
            ) CompensationRecovery.REPLAYED else CompensationRecovery.CONFLICT
            if (row.state != "NEEDS_ATTENTION" || row.generation != generation || row.max == Int.MAX_VALUE ||
                !restoreCheckpoint()) return@transaction CompensationRecovery.CONFLICT
            check(execute("UPDATE ns_compensation SET max_attempts=max_attempts+1,state='READY' WHERE id=:id", key.filterKeys { it == "id" }) == 1L)
            check(execute("""
                INSERT INTO ns_compensation_recovery(id,request_id,generation,workflow_version,actor,evidence)
                VALUES (:id,:request,:gen,:version,:actor,:evidence)
            """.trimIndent(), key + mapOf("gen" to generation, "version" to version, "actor" to actor, "evidence" to evidence)) == 1L)
            CompensationRecovery.RESUMED
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

    private suspend fun DbContext.lock(id: String): Row = checkNotNull(lockOrNull(id))

    private suspend fun DbContext.lockOrNull(id: String): Row? =
        fetchAll("SELECT state,generation,max_attempts FROM ns_compensation WHERE id=:id FOR UPDATE",
            mapOf("id" to id)).singleOrNull()?.let { Row(it.string("state"), it.int("generation"), it.int("max_attempts")) }

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
