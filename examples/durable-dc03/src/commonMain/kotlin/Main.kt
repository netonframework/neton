import neton.durable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import neton.core.Neton
import neton.core.config.getEnv
import neton.core.http.HttpHeaders
import neton.core.http.HttpStatus
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.api.DbContext
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.http.client.*
import neton.http.http
import neton.routing.*

@Serializable
private data class Credit(val identity: DeliveryIdentity, val amount: Int, val expiresAt: Long)

private suspend fun receive(db: DbContext, msg: Credit, target: String): DeliveryReceipt {
    val id = msg.identity
    require(id.namespace == "dc03" && id.tenant == "local" && id.source == "orders" && id.target == target)
    require(msg.amount in 1..1000)
    val digest = db.fetchAll("SELECT encode(sha256(convert_to(:canonical, 'UTF8')), 'hex') AS digest",
        mapOf("canonical" to "credit:v1:${msg.amount}")).single().string("digest")
    require(id.requestDigest == digest)
    return CommittedDelivery(db).receive(id, msg.expiresAt) {
        check(execute("UPDATE ledger SET balance=balance+:amount, applies=applies+1 WHERE id=1",
            mapOf("amount" to msg.amount)) == 1L)
    }
}

private suspend fun deliver(db: DbContext, client: HttpClient, url: String, auth: String, key: String): String {
    val row = db.fetchAll("SELECT wire, receipt FROM outbox WHERE message_id=:key", mapOf("key" to key)).single()
    row.stringOrNull("receipt")?.let { return "HANDED_OFF" }
    val wire = row.string("wire")
    val msg = Json.decodeFromString<Credit>(wire)
    check(msg.identity.messageId == key)
    // Commit this before any I/O. Process death at any later instruction leaves the original envelope recoverable.
    if (db.execute("UPDATE outbox SET state='UNKNOWN', attempts=attempts+1 WHERE message_id=:key AND receipt IS NULL",
            mapOf("key" to key)) == 0L) return "HANDED_OFF"
    val response = try {
        client.request(HttpClientRequest(HttpClientMethod.Post, url, HttpHeaders.of("Authorization" to auth), HttpClientBody.Json(wire)))
    } catch (e: CancellationException) { throw e }
    catch (_: HttpClientException) { return "UNKNOWN" }
    if (response.statusCode != 200) return "UNKNOWN"
    val ack = try { Json.decodeFromString<DeliveryReceipt>(response.body) } catch (_: IllegalArgumentException) { return "UNKNOWN" }
    if (!ack.confirmsSuccess(msg.identity)) return "UNKNOWN"
    val params = mapOf("receipt" to ack.receiptId, "key" to key, "wire" to wire)
    if (db.execute("UPDATE outbox SET state='HANDED_OFF',receipt=:receipt WHERE message_id=:key AND wire=:wire AND receipt IS NULL",
            params) == 0L) {
        check(db.fetchAll("SELECT receipt FROM outbox WHERE message_id=:key", mapOf("key" to key)).single().string("receipt") == ack.receiptId)
    }
    return "HANDED_OFF"
}

@OptIn(neton.core.annotations.InternalNetonApi::class)
fun main(args: Array<String>) {
    fun env(key: String) = requireNotNull(getEnv("DC03_" + key))
    val role = env("ROLE")
    require(role in setOf("sender", "receiver", "coordinator", "stock", "payment"))
    val auth = "Bearer " + env("TOKEN").also { require(it.length >= 32) }
    val db = SqlxDatabase.initialize(DatabaseConfig(DatabaseDriver.POSTGRESQL, env("DB")))
    val client = if (role != "receiver") HttpClient.create { } else null
    val auto = getEnv("DC03_SAGA_AUTO") == "1"
    val fixtureRenew = getEnv("DC03_SAGA_RENEW") == "1"
    val operator = getEnv("DC03_SAGA_OPERATOR")?.let { "Bearer " + it }
    val resource = getEnv("DC03_SAGA_RESOURCE")
    require(operator == null || (operator != auth && operator.length >= 39))
    var worker: Job? = null
    val saga = if (role in setOf("coordinator", "stock", "payment")) {
        HttpSaga(db, role, checkNotNull(client), auth, getEnv("DC03_SAGA_ATTEMPTS")?.toInt() ?: 8, auto,
            getEnv("DC03_SAGA_SCHEDULED") == "1") {
            target -> env("SAGA_" + target.uppercase())
        }
    } else null
    try {
        Neton.run(args) {
            http { port = env("PORT").toInt() }
            if (saga != null && auto) onStart {
                worker = CoroutineScope(Dispatchers.Default).launch { saga.runDispatcher() }
            }
            routing {
                get("/ready") { ctx -> ctx.response.text(role) }
                if (saga != null) {
                    if (role == "coordinator" && resource != null) {
                        for (operation in listOf("retire", "retirement", "activate")) post("/saga/$operation") { ctx ->
                            if (operator == null || ctx.request.headers["Authorization"] != operator) {
                                ctx.response.status = HttpStatus.FORBIDDEN
                                ctx.response.text("FORBIDDEN")
                            } else {
                                val fields = ctx.request.text().split('|')
                                val id = fields[0]
                                val result = when (operation) {
                                    "retire" -> if (fields.size == 3) saga.retire(id, fields[1], resource, fields[2])
                                        ?.let { Json.encodeToString(it) } else null
                                    "retirement" -> saga.retirement(id)?.let { Json.encodeToString(it) }
                                    else -> if (fields.size == 2) saga.activate(id, fields[1], resource,
                                            env("SAGA_SOURCE"), env("SAGA_SOURCE_URL"), operator) else null
                                }
                                if (result == null) ctx.response.status = HttpStatus.CONFLICT
                                ctx.response.text(result ?: "CONFLICT")
                            }
                        }
                    }
                    if (role == "coordinator") post("/saga/recover") { ctx ->
                        if (operator == null || ctx.request.headers["Authorization"] != operator) {
                            ctx.response.status = HttpStatus.FORBIDDEN
                            ctx.response.text("FORBIDDEN")
                        } else try {
                            val fields = ctx.request.text().split('|', limit = 5)
                            require(fields.size == 5)
                            val result = saga.recover(fields[0], fields[1], fields[2].toInt(), fields[3].toInt(), fields[4])
                            if (result.name == "CONFLICT") ctx.response.status = HttpStatus.CONFLICT
                            if (result.name == "FORBIDDEN") ctx.response.status = HttpStatus.FORBIDDEN
                            ctx.response.text(result.name)
                        } catch (_: IllegalArgumentException) {
                            ctx.response.status = HttpStatus.BAD_REQUEST
                            ctx.response.text("INVALID")
                        }
                    }
                    if (fixtureRenew) post("/saga/renew") { ctx ->
                        if (ctx.request.headers["Authorization"] != auth) {
                            ctx.response.status = HttpStatus.UNAUTHORIZED
                            ctx.response.text("unauthorized")
                        } else ctx.response.text(if (saga.renew(ctx.request.text())) "RENEWED" else "FENCED")
                    }
                    if (role == "coordinator") post("/saga/begin") { ctx ->
                        if (ctx.request.headers["Authorization"] != auth) {
                            ctx.response.status = HttpStatus.UNAUTHORIZED
                            ctx.response.text("unauthorized")
                        } else {
                            val (id, expiry) = ctx.request.text().split(':', limit = 2)
                            saga.begin(id, expiry.toLong())
                            ctx.response.text("READY")
                        }
                    }
                    post("/saga/receive") { ctx ->
                        if (ctx.request.headers["Authorization"] != auth) {
                            ctx.response.status = HttpStatus.UNAUTHORIZED
                            ctx.response.text("unauthorized")
                        } else try {
                            ctx.response.text(Json.encodeToString(saga.receive(ctx.request.text())), "application/json")
                        } catch (_: DeliveryConflict) {
                            ctx.response.status = HttpStatus.CONFLICT
                            ctx.response.text("ID_CONFLICT")
                        } catch (_: DeliveryExpired) {
                            ctx.response.status = HttpStatus.GONE
                            ctx.response.text("EXPIRED")
                        } catch (_: IllegalArgumentException) {
                            ctx.response.status = HttpStatus.BAD_REQUEST
                            ctx.response.text("INVALID")
                        }
                    }
                    if (role == "stock") post("/saga/outcome") { ctx ->
                        if (ctx.request.headers["Authorization"] != auth) {
                            ctx.response.status = HttpStatus.UNAUTHORIZED
                            ctx.response.text("unauthorized")
                        } else try {
                            val outcome = saga.outcome(ctx.request.text())
                            if (outcome == null) {
                                ctx.response.status = HttpStatus.NOT_FOUND
                                ctx.response.text("UNKNOWN")
                            } else ctx.response.text(Json.encodeToString(outcome), "application/json")
                        } catch (_: DeliveryConflict) {
                            ctx.response.status = HttpStatus.CONFLICT
                            ctx.response.text("ID_CONFLICT")
                        } catch (_: IllegalArgumentException) {
                            ctx.response.status = HttpStatus.BAD_REQUEST
                            ctx.response.text("INVALID")
                        }
                    }
                    post("/saga/dispatch") { ctx ->
                        if (ctx.request.headers["Authorization"] != auth) {
                            ctx.response.status = HttpStatus.UNAUTHORIZED
                            ctx.response.text("unauthorized")
                        } else ctx.response.text(saga.dispatch(ctx.request.text()))
                    }
                }
                if (role == "receiver") post("/receive") { ctx ->
                    if (ctx.request.headers["Authorization"] != auth) {
                        ctx.response.status = HttpStatus.UNAUTHORIZED
                        ctx.response.text("unauthorized")
                    } else try {
                        val receipt = receive(db, Json.decodeFromString(ctx.request.text()), env("TARGET"))
                        ctx.response.text(Json.encodeToString(receipt), "application/json")
                    } catch (_: DeliveryConflict) {
                        ctx.response.status = HttpStatus.CONFLICT
                        ctx.response.text("ID_CONFLICT")
                    } catch (_: DeliveryExpired) {
                        ctx.response.status = HttpStatus.GONE
                        ctx.response.text("EXPIRED")
                    }
                }
                if (role == "sender") post("/produce") { ctx ->
                    if (ctx.request.headers["Authorization"] != auth) {
                        ctx.response.status = HttpStatus.UNAUTHORIZED
                        ctx.response.text("unauthorized")
                    } else {
                        val wire = ctx.request.text()
                        val credit = Json.decodeFromString<Credit>(wire)
                        val key = credit.identity.messageId
                        db.transaction {
                            execute("INSERT INTO orders(message_id,amount) VALUES (:key,:amount)", mapOf("key" to key, "amount" to credit.amount))
                            execute("INSERT INTO outbox(message_id,wire) VALUES (:key,:wire)", mapOf("key" to key, "wire" to wire))
                        }
                        ctx.response.text("READY")
                    }
                }
                if (role == "sender") post("/recover") { ctx ->
                    if (ctx.request.headers["Authorization"] != auth) {
                        ctx.response.status = HttpStatus.UNAUTHORIZED
                        ctx.response.text("unauthorized")
                    } else {
                        val next = db.fetchAll("SELECT message_id FROM outbox WHERE state IN ('READY','UNKNOWN') ORDER BY message_id LIMIT 1")
                            .singleOrNull()
                        ctx.response.text(if (next == null) "IDLE" else deliver(db, checkNotNull(client), env("URL"), auth, next.string("message_id")))
                    }
                }
            }
        }
    } finally { runBlocking {
        worker?.cancelAndJoin()
        try { client?.close() } finally { SqlxDatabase.close() }
    } }
}
