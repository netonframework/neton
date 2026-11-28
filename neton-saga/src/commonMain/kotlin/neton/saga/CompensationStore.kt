package neton.saga

import neton.database.api.DbContext
import neton.database.sql.PostgresDialect

sealed interface CompensationClaim {
    class Acquired internal constructor(val id: String, val generation: Int) : CompensationClaim
    enum class Unavailable : CompensationClaim { BUSY, HANDED_OFF, NEEDS_ATTENTION, BACKOFF, AT_CAPACITY, DRAINING, RETIRED }
}

enum class CompensationFinish { HANDED_OFF, RETRY_READY, NEEDS_ATTENTION, FENCED }
enum class CompensationRecovery { RESUMED, REPLAYED, CONFLICT, FORBIDDEN }

class CompensationBackoff(private val baseMillis: Int, private val capMillis: Int) {
    init { require(baseMillis > 0 && capMillis >= baseMillis) }

    // Equal jitter avoids a zero-delay retry while keeping the delay below the configured ceiling.
    fun delayMillis(attempt: Int, jitter: kotlin.random.Random = kotlin.random.Random.Default): Int {
        require(attempt > 0)
        val window = (baseMillis.toLong() shl (attempt - 1).coerceIn(0, 31)).coerceAtMost(capMillis.toLong())
        return jitter.nextLong((window + 1) / 2, window + 1).toInt()
    }
}

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
            val capacity = admission()
            val row = lock(id)
            if (row.retired) return@transaction CompensationClaim.Unavailable.RETIRED
            if (draining(id)) return@transaction CompensationClaim.Unavailable.DRAINING
            when (row.state) {
                "HANDED_OFF" -> return@transaction CompensationClaim.Unavailable.HANDED_OFF
                "NEEDS_ATTENTION" -> return@transaction CompensationClaim.Unavailable.NEEDS_ATTENTION
                "LEASED" -> if (unexpired(id)) return@transaction CompensationClaim.Unavailable.BUSY
            }
            if (row.generation == row.max) {
                attention(id, row.generation, "LEASE_EXPIRED", onAttention)
                return@transaction CompensationClaim.Unavailable.NEEDS_ATTENTION
            }
            if (fetchAll("SELECT 1 FROM ns_compensation WHERE id=:id AND next_attempt_at > clock_timestamp()",
                    mapOf("id" to id)).isNotEmpty()) return@transaction CompensationClaim.Unavailable.BACKOFF
            if (fetchAll("SELECT count(*) AS n FROM ns_compensation WHERE state='LEASED' AND lease_until > clock_timestamp()")
                    .single().long("n") >= capacity) return@transaction CompensationClaim.Unavailable.AT_CAPACITY
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
            // An admission scan cannot miss a concurrently extended lease.
            admission()
            val row = lock(claim.id)
            if (row.retired || draining(claim.id) || row.state != "LEASED" || row.generation != claim.generation) return@transaction false
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
            if (row.retired || draining(id) || row.state != "NEEDS_ATTENTION" || row.generation != generation || row.max == Int.MAX_VALUE ||
                !restoreCheckpoint()) return@transaction CompensationRecovery.CONFLICT
            check(execute("UPDATE ns_compensation SET max_attempts=max_attempts+1,state='READY',next_attempt_at=clock_timestamp() WHERE id=:id",
                mapOf("id" to id)) == 1L)
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
        retryDelayMillis: Int = 0,
    ): CompensationFinish {
        require(retryDelayMillis >= 0)
        check(!db.inTransaction())
        return db.transaction {
            val id = claim.id
            val row = lock(id)
            if (row.retired || row.state != "LEASED" || row.generation != claim.generation || !unexpired(id)) {
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
                    check(execute("""
                        UPDATE ns_compensation SET state='READY',lease_until=NULL,
                          next_attempt_at=clock_timestamp()+CAST(:ms AS bigint)*interval '1 millisecond' WHERE id=:id
                    """.trimIndent(), mapOf("id" to id, "ms" to retryDelayMillis)) == 1L)
                    CompensationFinish.RETRY_READY
                }
            }
        }
    }

    /** Serialize a business-result writer with retirement, before writing the result Inbox. */
    suspend fun ownsResult(id: String): Boolean {
        check(db.inTransaction())
        return db.lockOrNull(id)?.retired == false
    }

    suspend fun retirement(id: String): CompensationTransfer? {
        check(!db.inTransaction())
        return db.transaction {
            if (lockOrNull(id)?.retired == true) transfer(id) else null
        }
    }

    /** Persist the drain intent before waiting. Active workers may finish but cannot renew. */
    suspend fun retire(id: String, plan: String, source: String, target: String,
        onAttention: suspend DbContext.() -> Unit,
        snapshot: suspend DbContext.() -> String?,
    ): CompensationTransfer? {
        require(plan.isNotBlank() && source.isNotBlank() && target.isNotBlank() && source != target)
        check(!db.inTransaction())
        return db.transaction {
            val row = lockOrNull(id) ?: return@transaction null
            transfer(id)?.let {
                return@transaction it.takeIf { row.retired && it.plan == plan && it.source == source && it.target == target }
            }
            if (row.retired || row.generation == 0) return@transaction null
            val keys = mapOf("id" to id, "plan" to plan, "source" to source, "target" to target)
            execute("INSERT INTO ns_compensation_drain(id,plan,source,target) VALUES(:id,:plan,:source,:target) ON CONFLICT DO NOTHING", keys)
            if (fetchAll("SELECT 1 FROM ns_compensation_drain WHERE id=:id AND plan=:plan AND source=:source AND target=:target",
                    keys).isEmpty()) return@transaction null
            if (row.state == "LEASED") {
                if (unexpired(id)) return@transaction null
                // An expired worker is fenced, and its attempt remains consumed.
                if (row.generation == row.max) attention(id, row.generation, "LEASE_EXPIRED", onAttention)
                else setState(id, "READY")
            }
            val payload = snapshot() ?: return@transaction null
            insertHandoff(id, plan, source, target, payload, history(id, "ns_compensation_audit"),
                history(id, "ns_compensation_recovery"))
            check(execute("UPDATE ns_compensation SET retired=true WHERE id=:id", mapOf("id" to id)) == 1L)
            transfer(id)
        }
    }

    /** Call only with proof fetched from the preconfigured trusted source, never a client-supplied snapshot. */
    suspend fun activate(proof: CompensationTransfer, local: String, expectedSource: String,
        import: suspend DbContext.(String) -> Unit,
    ): String? {
        check(!db.inTransaction())
        if (proof.target != local || proof.source != expectedSource || local == expectedSource ||
            proof.generation !in 1..proof.budget ||
            proof.state !in setOf("READY", "HANDED_OFF", "NEEDS_ATTENTION") ||
            (proof.state == "READY" && proof.generation == proof.budget)) return null
        return db.transaction {
            val key = mapOf("id" to proof.id)
            val inserted = execute("""
                INSERT INTO ns_compensation(id,max_attempts,generation,next_attempt_at,state)
                VALUES(:id,:max,:gen,CAST(:due AS timestamptz),:state) ON CONFLICT(id) DO NOTHING
            """.trimIndent(), key + mapOf("max" to proof.budget, "gen" to proof.generation, "due" to proof.dueAt, "state" to proof.state))
            if (inserted == 0L) {
                lock(proof.id)
                val previous = fetchAll("""
                    SELECT plan,source,target,payload,generation,budget,state,audit,recovery,activation_id::text AS activation,
                           due_at=CAST(:due AS timestamptz) AS due_matches
                    FROM ns_compensation_handoff WHERE id=:id
                """.trimIndent(), key + ("due" to proof.dueAt)).singleOrNull()
                return@transaction previous?.takeIf {
                    it.string("plan") == proof.plan && it.string("source") == proof.source &&
                        it.string("target") == proof.target && it.string("payload") == proof.payload &&
                        it.int("generation") == proof.generation && it.int("budget") == proof.budget && it.boolean("due_matches") &&
                        it.string("state") == proof.state && it.string("audit") == proof.audit && it.string("recovery") == proof.recovery
                }?.string("activation")
            }
            insertHandoff(proof.id, proof.plan, proof.source, proof.target, proof.payload, proof.audit, proof.recovery)
            restoreHistory("ns_compensation_audit", proof.id, proof.audit)
            restoreHistory("ns_compensation_recovery", proof.id, proof.recovery)
            import(proof.payload)
            checkNotNull(activation(proof.id, proof.plan, local, expectedSource))
        }
    }

    suspend fun activation(id: String, plan: String, local: String, source: String): String? =
        db.fetchAll("""
            SELECT h.activation_id::text AS activation FROM ns_compensation_handoff h JOIN ns_compensation c USING(id)
            WHERE h.id=:id AND h.plan=:plan AND h.target=:target AND h.source=:source AND NOT c.retired
        """.trimIndent(), mapOf("id" to id, "plan" to plan, "target" to local, "source" to source))
            .singleOrNull()?.string("activation")

    private suspend fun DbContext.draining(id: String): Boolean =
        fetchAll("SELECT 1 FROM ns_compensation_drain WHERE id=:id", mapOf("id" to id)).isNotEmpty()

    private suspend fun DbContext.history(id: String, table: String): String =
        fetchAll("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)), '[]')::text AS data FROM $table t WHERE id=:id",
            mapOf("id" to id)).single().string("data")

    private suspend fun DbContext.restoreHistory(table: String, id: String, payload: String) {
        val key = mapOf("id" to id, "payload" to payload)
        val rows = "jsonb_populate_recordset(NULL::$table, CAST(:payload AS jsonb))"
        check(fetchAll("SELECT 1 FROM $rows r WHERE r.id IS DISTINCT FROM :id", key).isEmpty())
        execute("INSERT INTO $table SELECT * FROM $rows", mapOf("payload" to payload))
    }

    private suspend fun DbContext.insertHandoff(id: String, plan: String, source: String, target: String, payload: String,
        audit: String, recovery: String) {
        check(execute("""
            INSERT INTO ns_compensation_handoff(id,plan,source,target,payload,generation,budget,due_at,state,audit,recovery)
            SELECT id,:plan,:source,:target,:payload,generation,max_attempts,next_attempt_at,state,:audit,:recovery
            FROM ns_compensation WHERE id=:id
        """.trimIndent(),
            mapOf("id" to id, "plan" to plan, "source" to source, "target" to target, "payload" to payload,
                "audit" to audit, "recovery" to recovery)) == 1L)
    }

    private suspend fun DbContext.transfer(id: String): CompensationTransfer? =
        fetchAll("""
            SELECT generation,budget,due_at::text AS due,plan,source,target,payload,state,audit,recovery
            FROM ns_compensation_handoff WHERE id=:id
        """.trimIndent(), mapOf("id" to id)).singleOrNull()?.let {
            CompensationTransfer(id, it.string("plan"), it.string("source"), it.string("target"),
                it.int("generation"), it.int("budget"), it.string("due"), it.string("payload"),
                it.string("state"), it.string("audit"), it.string("recovery"))
        }

    private data class Row(val state: String, val generation: Int, val max: Int, val retired: Boolean)

    private suspend fun DbContext.admission(): Int =
        fetchAll("SELECT capacity FROM ns_compensation_admission WHERE singleton FOR UPDATE").single().int("capacity")

    private suspend fun DbContext.lock(id: String): Row = checkNotNull(lockOrNull(id))

    private suspend fun DbContext.lockOrNull(id: String): Row? =
        fetchAll("SELECT state,generation,max_attempts,retired FROM ns_compensation WHERE id=:id FOR UPDATE",
            mapOf("id" to id)).singleOrNull()?.let { Row(it.string("state"), it.int("generation"), it.int("max_attempts"), it.boolean("retired")) }

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
