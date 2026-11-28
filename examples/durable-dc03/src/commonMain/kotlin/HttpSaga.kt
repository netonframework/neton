import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.core.http.HttpHeaders
import neton.database.api.DbContext
import neton.durable.CommittedDelivery
import neton.durable.DeliveryConflict
import neton.durable.DeliveryIdentity
import neton.durable.DeliveryReceipt
import neton.http.client.*
import neton.saga.CompensationClaim
import neton.saga.CompensationFinish
import neton.saga.CompensationStore
import neton.saga.CompensationRecovery
import neton.saga.CompensationBackoff
import neton.saga.CompensationTransfer
import neton.saga.ExternalOutcomeQuery
import neton.saga.ExternalReconciler

@Serializable
private data class SagaWire(val saga: String, val action: String, val result: String,
    val expiry: Long, val identity: DeliveryIdentity)

@Serializable
internal data class SagaOutcome(val receipt: DeliveryReceipt, val resultWire: String)

@Serializable
internal data class HandoffWire(val id: String, val plan: String, val source: String, val target: String,
    val generation: Int, val budget: Int, val dueAt: String, val payload: String,
    val state: String = "READY", val audit: String = "[]", val recovery: String = "[]") {
    constructor(value: CompensationTransfer) : this(value.id, value.plan, value.source, value.target,
        value.generation, value.budget, value.dueAt, value.payload, value.state, value.audit, value.recovery)
    fun proof() = CompensationTransfer(id, plan, source, target, generation, budget, dueAt, payload, state, audit, recovery)
}

@Serializable
private data class CheckoutSnapshot(val saga: String, val version: Int, val wire: String,
    val state: String, val outbox: String, val inbox: String)

/** Fixed checkout example. Receipt acknowledgement is independent of Saga advancement. */
internal class HttpSaga(private val db: DbContext, private val role: String, private val client: HttpClient,
    private val auth: String, private val compensationAttempts: Int, private val auto: Boolean,
    private val scheduled: Boolean,
    private val endpoint: (String) -> String) {
    private val inbox = CommittedDelivery(db)
    private val compensation = CompensationStore(db)
    private val leases = mutableMapOf<String, CompensationClaim.Acquired>()
    private val leaseLock = Mutex()
    private val backoff = CompensationBackoff(2_000, 30_000)

    suspend fun renew(id: String): Boolean {
        val lease = leaseLock.withLock { leases[id] } ?: return false
        return compensation.renew(lease, 30_000)
    }

    suspend fun recover(saga: String, request: String, generation: Int, version: Int, evidence: String): CompensationRecovery =
        compensation.recover("$saga:release:command", request, generation, version, "checkout-operator", evidence,
            authorize = { _, _, proof -> proof.startsWith("receiver-idempotency-verified:") && proof.substringAfter(':').isNotBlank() },
            restoreCheckpoint = {
                execute("UPDATE saga_state SET state='COMPENSATING',version=version+1 WHERE id=:id AND state='NEEDS_ATTENTION' AND version=:version",
                    mapOf("id" to saga, "version" to version)) == 1L
            })

    suspend fun runDispatcher(): Unit {
        while (true) {
            dispatch("")
            delay(200)
        }
    }

    private suspend fun DbContext.wire(saga: String, action: String, result: String, expiry: Long): SagaWire {
        require(saga.length == 32 && saga.all { it in "0123456789abcdef" })
        val participant = when (action) {
            "reserve", "release" -> "stock"
            "charge" -> "payment"
            else -> throw IllegalArgumentException("Unknown checkout action")
        }
        require(result == "" || result == "ok" || result == "declined")
        val op = "$saga:$action"
        val src = if (result.isEmpty()) "coordinator" else participant
        val dst = if (result.isEmpty()) participant else "coordinator"
        val mid = if (result.isEmpty()) "$op:command" else "$op:result"
        val digest = fetchAll("SELECT encode(sha256(convert_to(:input, 'UTF8')), 'hex') AS digest",
            mapOf("input" to "checkout:v1:$op:$result:$expiry")).single().string("digest")
        return SagaWire(saga, action, result, expiry, DeliveryIdentity(1, "checkout", "local", src, dst, mid, op, digest))
    }

    private suspend fun DbContext.enqueue(message: SagaWire) {
        check(execute("INSERT INTO saga_queue(message_id,wire) VALUES(:id,:wire)",
            mapOf("id" to message.identity.messageId, "wire" to Json.encodeToString(message))) == 1L)
    }

    suspend fun begin(saga: String, expiry: Long) {
        check(role == "coordinator")
        db.transaction {
            val command = wire(saga, "reserve", "", expiry)
            if (execute("INSERT INTO saga_state(id,state) VALUES(:id,'RESERVING') ON CONFLICT DO NOTHING",
                    mapOf("id" to saga)) == 1L) enqueue(command)
        }
    }

    private suspend fun validated(raw: String): SagaWire {
        val msg = Json.decodeFromString<SagaWire>(raw)
        val expected = db.wire(msg.saga, msg.action, msg.result, msg.expiry)
        if (msg != expected || msg.identity.target != role) throw DeliveryConflict()
        return msg
    }

    suspend fun receive(raw: String): DeliveryReceipt {
        val msg = validated(raw)
        return inbox.receiveGuarded(msg.identity, msg.expiry, before = {
            if (role == "coordinator" && msg.action == "release" &&
                !compensation.ownsResult("${msg.saga}:release:command")) throw DeliveryConflict()
        }) { applyMessage(msg) }
    }

    suspend fun retire(saga: String, plan: String, local: String, target: String): HandoffWire? {
        return compensation.retire("$saga:release:command", plan, local, target, onAttention = {
            execute("UPDATE saga_state SET state='NEEDS_ATTENTION',version=version+1 WHERE id=:id AND state='COMPENSATING'",
                mapOf("id" to saga))
        }) {
            fetchAll("""
                SELECT s.version,s.state,q.wire,
                  (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY o.message_id), '[]')::text
                   FROM saga_queue o WHERE split_part(o.message_id,':',1)=:id) AS outbox,
                  (SELECT COALESCE(jsonb_agg(to_jsonb(i) ORDER BY i.message_id), '[]')::text
                   FROM nd_dc03_inbox i WHERE i.namespace='checkout' AND i.target_id='coordinator'
                     AND split_part(i.message_id,':',1)=:id) AS inbox
                FROM saga_state s JOIN saga_queue q ON q.message_id=:command
                WHERE s.id=:id AND s.state IN ('COMPENSATING','COMPENSATED','NEEDS_ATTENTION')
            """.trimIndent(), mapOf("id" to saga, "command" to "$saga:release:command")).singleOrNull()?.let {
                Json.encodeToString(CheckoutSnapshot(saga, it.int("version"), it.string("wire"),
                    it.string("state"), it.string("outbox"), it.string("inbox")))
            }
        }?.let(::HandoffWire)
    }

    suspend fun retirement(saga: String): HandoffWire? =
        compensation.retirement("$saga:release:command")?.let(::HandoffWire)

    suspend fun activate(saga: String, plan: String, local: String, source: String, sourceUrl: String, control: String): String? {
        compensation.activation("$saga:release:command", plan, local, source)?.let { return it }
        val response = try {
            withTimeoutOrNull(8_000) {
                client.request(HttpClientRequest(HttpClientMethod.Post, "$sourceUrl/saga/retirement",
                    HttpHeaders.of("Authorization" to control), HttpClientBody.Json(saga)))
            }
        } catch (_: HttpClientException) { null } ?: return null
        if (response.statusCode != 200) return null
        val proof = Json.decodeFromString<HandoffWire>(response.body)
        if (proof.id != "$saga:release:command" || proof.plan != plan) return null
        val snapshot = Json.decodeFromString<CheckoutSnapshot>(proof.payload)
        val command = Json.decodeFromString<SagaWire>(snapshot.wire)
        if (snapshot.saga != saga || command.saga != saga || command.action != "release" || command.result.isNotEmpty() ||
            db.wire(saga, "release", "", command.expiry) != command ||
            snapshot.state !in setOf("COMPENSATING", "COMPENSATED", "NEEDS_ATTENTION")) return null
        return compensation.activate(proof.proof(), local, source) {
            check(execute("INSERT INTO saga_state(id,state,version) VALUES(:id,:state,:version)",
                mapOf("id" to saga, "state" to snapshot.state, "version" to snapshot.version)) == 1L)
            execute("INSERT INTO saga_queue SELECT * FROM jsonb_populate_recordset(NULL::saga_queue,CAST(:data AS jsonb))",
                mapOf("data" to snapshot.outbox))
            execute("INSERT INTO nd_dc03_inbox SELECT * FROM jsonb_populate_recordset(NULL::nd_dc03_inbox,CAST(:data AS jsonb))",
                mapOf("data" to snapshot.inbox))
            check(fetchAll("SELECT wire FROM saga_queue WHERE message_id=:id", mapOf("id" to proof.id))
                .single().string("wire") == snapshot.wire)
        }
    }

    suspend fun outcome(raw: String): SagaOutcome? {
        val cmd = validated(raw)
        if (role != "stock" || cmd.action != "release" || cmd.result.isNotEmpty()) throw DeliveryConflict()
        val receipt = inbox.lookup(cmd.identity, cmd.expiry) ?: return null
        val result = db.wire(cmd.saga, cmd.action, "ok", cmd.expiry)
        val recorded = db.fetchAll("SELECT wire FROM saga_queue WHERE message_id=:id",
            mapOf("id" to result.identity.messageId)).singleOrNull()?.string("wire") ?: throw DeliveryConflict()
        return SagaOutcome(receipt, recorded)
    }

    private suspend fun DbContext.applyMessage(msg: SagaWire) {
            val key = mapOf("id" to msg.saga)
            if (role == "coordinator") {
                val command = wire(msg.saga, msg.action, "", msg.expiry)
                val sent = fetchAll("SELECT wire FROM saga_queue WHERE message_id=:id",
                    mapOf("id" to command.identity.messageId)).singleOrNull()?.string("wire")
                if (sent != Json.encodeToString(command)) throw DeliveryConflict()
                val (before, after, next) = when (msg.action to msg.result) {
                    "reserve" to "ok" -> Triple("RESERVING", "CHARGING", "charge")
                    "charge" to "declined" -> Triple("CHARGING", "COMPENSATING", "release")
                    "charge" to "ok" -> Triple("CHARGING", "SUCCEEDED", null)
                    "release" to "ok" -> Triple("COMPENSATING", "COMPENSATED", null)
                    else -> throw DeliveryConflict()
                }
                if (execute("UPDATE saga_state SET state=:after,version=version+1 WHERE id=:id AND state=:before",
                        key + mapOf("before" to before, "after" to after)) != 1L) throw DeliveryConflict()
                if (next != null) {
                    val commandToSend = wire(msg.saga, next, "", msg.expiry)
                    enqueue(commandToSend)
                    if (next == "release") compensation.register(commandToSend.identity.messageId, compensationAttempts)
                }
            } else {
                check(msg.result.isEmpty())
                val result = when (msg.action) {
                    "reserve" -> {
                        execute("INSERT INTO reservation(saga_id,reserved) VALUES(:id,true)", key)
                        "ok"
                    }
                    "charge" -> {
                        // This test merchant declines the charge. Persist the decision, not a debit.
                        execute("INSERT INTO payment_decision(saga_id) VALUES(:id)", key)
                        "declined"
                    }
                    "release" -> {
                        check(execute("UPDATE reservation SET reserved=false,releases=releases+1 WHERE saga_id=:id AND reserved", key) == 1L)
                        "ok"
                    }
                    else -> throw DeliveryConflict()
                }
                enqueue(wire(msg.saga, msg.action, result, msg.expiry))
            }
    }

    suspend fun dispatch(id: String): String {
        val pending = id.ifEmpty {
            db.fetchAll("""
                SELECT message_id FROM saga_queue q WHERE receipt IS NULL AND NOT EXISTS (
                    SELECT 1 FROM ns_compensation c WHERE c.id=q.message_id AND
                      (c.retired OR EXISTS(SELECT 1 FROM ns_compensation_drain d WHERE d.id=c.id) OR
                       c.state='NEEDS_ATTENTION' OR c.next_attempt_at > clock_timestamp() OR
                       (c.state='LEASED' AND c.lease_until > clock_timestamp()))
                ) ORDER BY message_id LIMIT 1
            """.trimIndent())
                .singleOrNull()?.string("message_id") ?: return "IDLE"
        }
        val key = mapOf("id" to pending)
        val row = db.fetchAll("SELECT wire,receipt FROM saga_queue WHERE message_id=:id", key).singleOrNull()
            ?: return "IDLE"
        if (row.stringOrNull("receipt") != null) return "HANDED_OFF"
        val raw = row.string("wire")
        val msg = Json.decodeFromString<SagaWire>(raw)
        if (msg.action == "release" && msg.result.isEmpty()) {
            val onAttention: suspend DbContext.() -> Unit = {
                // An authoritative callback can beat the transport ACK. Never undo its completed result.
                execute("UPDATE saga_state SET state='NEEDS_ATTENTION',version=version+1 WHERE id=:id AND state='COMPENSATING'",
                    mapOf("id" to msg.saga))
            }
            return when (val lease = compensation.claim(pending, 30_000, onAttention)) {
                is CompensationClaim.Unavailable -> lease.name
                is CompensationClaim.Acquired -> {
                    val (receipt, resultWire) = sendClaimed(lease, raw, msg)
                    val result = compensation.finish(lease, receipt != null, {
                        check(execute("UPDATE saga_queue SET receipt=:receipt WHERE message_id=:id AND receipt IS NULL",
                            key + ("receipt" to checkNotNull(receipt).receiptId)) == 1L)
                        if (resultWire != null) {
                            inbox.applyInTransaction(resultWire.identity, resultWire.expiry) { applyMessage(resultWire) }
                        }
                    }, onAttention, if (scheduled || auto) backoff.delayMillis(lease.generation) else 0)
                    if (result == CompensationFinish.RETRY_READY) "UNKNOWN" else result.name
                }
            }
        }
        val receipt = send(raw, msg) ?: return "UNKNOWN"
        if (db.execute("UPDATE saga_queue SET receipt=:receipt WHERE message_id=:id AND receipt IS NULL",
                key + ("receipt" to receipt.receiptId)) == 0L) {
            check(db.fetchAll("SELECT receipt FROM saga_queue WHERE message_id=:id", key).single().string("receipt") == receipt.receiptId)
        }
        return "HANDED_OFF"
    }

    private suspend fun sendClaimed(lease: CompensationClaim.Acquired, raw: String, msg: SagaWire):
        Pair<DeliveryReceipt?, SagaWire?> =
        coroutineScope {
            leaseLock.withLock { leases[lease.id] = lease }
            val renewer = if (auto) launch {
                while (true) {
                    delay(10_000)
                    if (!compensation.renew(lease, 30_000)) break
                }
            } else null
            try {
                if ((auto || scheduled) && lease.generation > 1) {
                    val query = ExternalOutcomeQuery<SagaWire, SagaOutcome> {
                        post(raw, it, "/saga/outcome")?.takeIf { r -> r.statusCode == 200 }
                            ?.let { r -> Json.decodeFromString<SagaOutcome>(r.body) }
                    }
                    val reconciler = ExternalReconciler(query) { expected, outcome ->
                        val reported = validated(outcome.resultWire)
                        if (outcome.receipt.confirmsSuccess(expected.identity) && reported.saga == expected.saga &&
                            reported.action == expected.action && reported.expiry == expected.expiry && reported.result == "ok"
                        ) outcome.receipt to reported else null
                    }
                    reconciler.resolve(msg) ?: (null to null)
                } else send(raw, msg) to null
            } catch (_: IllegalArgumentException) {
                null to null
            } catch (_: DeliveryConflict) {
                null to null
            } finally {
                // No external work in this cleanup: only the local handle is removed.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    renewer?.cancelAndJoin()
                    leaseLock.withLock { if (leases[lease.id] === lease) leases.remove(lease.id) }
                }
            }
        }

    private suspend fun send(raw: String, msg: SagaWire): DeliveryReceipt? {
        val response = post(raw, msg, "/saga/receive") ?: return null
        if (response.statusCode != 200) return null
        val receipt = try { Json.decodeFromString<DeliveryReceipt>(response.body) }
        catch (_: IllegalArgumentException) { return null }
        return receipt.takeIf { it.confirmsSuccess(msg.identity) }
    }

    private suspend fun post(raw: String, msg: SagaWire, path: String): HttpClientResponse? =
        try {
            withTimeoutOrNull(8_000) {
                client.request(HttpClientRequest(HttpClientMethod.Post, endpoint(msg.identity.target) + path,
                    HttpHeaders.of("Authorization" to auth), HttpClientBody.Json(raw)))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: HttpClientException) { null }
}
