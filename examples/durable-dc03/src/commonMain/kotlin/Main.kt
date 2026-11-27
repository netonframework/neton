import neton.durable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    require(role in setOf("sender", "receiver"))
    val auth = "Bearer " + env("TOKEN").also { require(it.length >= 32) }
    val db = SqlxDatabase.initialize(DatabaseConfig(DatabaseDriver.POSTGRESQL, env("DB")))
    val client = if (role == "sender") HttpClient.create { } else null
    try {
        Neton.run(args) {
            http { port = env("PORT").toInt() }
            routing {
                get("/ready") { ctx -> ctx.response.text(role) }
                if (client == null) post("/receive") { ctx ->
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
                } else post("/recover") { ctx ->
                    if (ctx.request.headers["Authorization"] != auth) {
                        ctx.response.status = HttpStatus.UNAUTHORIZED
                        ctx.response.text("unauthorized")
                    } else {
                        val next = db.fetchAll("SELECT message_id FROM outbox WHERE state IN ('READY','UNKNOWN') ORDER BY message_id LIMIT 1")
                            .singleOrNull()
                        ctx.response.text(if (next == null) "IDLE" else deliver(db, client, env("URL"), auth, next.string("message_id")))
                    }
                }
            }
        }
    } finally { runBlocking { try { client?.close() } finally { SqlxDatabase.close() } } }
}
