import kotlinx.coroutines.CancellationException
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

@Serializable
private data class SagaWire(val saga: String, val action: String, val result: String,
    val expiry: Long, val identity: DeliveryIdentity)

/** Fixed checkout example. Receipt acknowledgement is independent of Saga advancement. */
internal class HttpSaga(private val db: DbContext, private val role: String, private val client: HttpClient,
    private val auth: String, private val compensationAttempts: Int, private val endpoint: (String) -> String) {
    private val inbox = CommittedDelivery(db)
    private val compensation = CompensationStore(db)

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

    suspend fun receive(raw: String): DeliveryReceipt {
        val msg = Json.decodeFromString<SagaWire>(raw)
        val expected = db.wire(msg.saga, msg.action, msg.result, msg.expiry)
        if (msg != expected || msg.identity.target != role) throw DeliveryConflict()
        return inbox.receive(expected.identity, msg.expiry) {
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
    }

    suspend fun dispatch(id: String): String {
        val pending = id.ifEmpty {
            db.fetchAll("""
                SELECT message_id FROM saga_queue q WHERE receipt IS NULL AND NOT EXISTS (
                    SELECT 1 FROM ns_compensation c WHERE c.id=q.message_id AND c.state='NEEDS_ATTENTION'
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
                    val receipt = send(raw, msg)
                    val result = compensation.finish(lease, receipt != null, {
                        check(execute("UPDATE saga_queue SET receipt=:receipt WHERE message_id=:id AND receipt IS NULL",
                            key + ("receipt" to checkNotNull(receipt).receiptId)) == 1L)
                    }, onAttention)
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

    private suspend fun send(raw: String, msg: SagaWire): DeliveryReceipt? {
        val response = try {
            client.request(HttpClientRequest(HttpClientMethod.Post, endpoint(msg.identity.target) + "/saga/receive",
                HttpHeaders.of("Authorization" to auth), HttpClientBody.Json(raw)))
        } catch (e: CancellationException) { throw e }
        catch (_: HttpClientException) { return null }
        if (response.statusCode != 200) return null
        val receipt = try { Json.decodeFromString<DeliveryReceipt>(response.body) }
        catch (_: IllegalArgumentException) { return null }
        return receipt.takeIf { it.confirmsSuccess(msg.identity) }
    }
}
