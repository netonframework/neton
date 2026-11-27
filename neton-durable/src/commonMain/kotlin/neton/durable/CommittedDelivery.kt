package neton.durable

import kotlinx.serialization.Serializable
import neton.database.api.DbContext
import neton.database.sql.PostgresDialect

@Serializable
data class DeliveryIdentity(val protocolVersion: Int, val namespace: String, val tenant: String,
    val source: String, val target: String, val messageId: String, val invocationId: String, val requestDigest: String)

@Serializable
data class DeliveryReceipt(val identity: DeliveryIdentity, val receiptId: String, val state: String, val resultVersion: Int) {
    fun confirmsSuccess(expected: DeliveryIdentity): Boolean =
        identity == expected && expected.protocolVersion == 1 && state == "SUCCEEDED" && resultVersion == 1 && receiptId.isNotBlank()
}

class DeliveryConflict : Exception()
class DeliveryExpired : Exception()

/** Synchronous local-effect profile only. Authenticate scope and verify the canonical payload digest before entry. */
class CommittedDelivery(private val db: DbContext) {
    init { require(db.dialect is PostgresDialect) }

    suspend fun receive(id: DeliveryIdentity, expiresAt: Long, effect: suspend DbContext.() -> Unit): DeliveryReceipt {
        require(id.protocolVersion == 1 && listOf(id.namespace, id.tenant, id.source, id.target, id.messageId,
            id.invocationId).all { it.length in 1..128 } && id.requestDigest.length == 64)
        check(!db.inTransaction()) { "A receipt cannot escape an uncommitted outer transaction" }
        val scope = mapOf("ns" to id.namespace, "tenant" to id.tenant, "src" to id.source, "dst" to id.target)
        val key = scope + ("msg" to id.messageId)
        val where = "namespace=:ns AND tenant=:tenant AND source_id=:src AND target_id=:dst"
        return db.transaction {
            val inserted = fetchAll("""
                INSERT INTO nd_dc03_inbox(namespace,tenant,source_id,target_id,message_id,invocation_id,digest,expires_at)
                SELECT :ns,:tenant,:src,:dst,:msg,:op,:digest,:expiry WHERE :expiry > extract(epoch from clock_timestamp())
                ON CONFLICT DO NOTHING RETURNING message_id
            """.trimIndent(), key + mapOf("op" to id.invocationId, "digest" to id.requestDigest, "expiry" to expiresAt))
            val row = fetchAll("SELECT invocation_id,digest,expires_at,receipt_id::text AS rid FROM nd_dc03_inbox WHERE $where AND message_id=:msg",
                key).singleOrNull()
            if (row == null) {
                if (fetchAll("SELECT 1 FROM nd_dc03_inbox WHERE $where AND invocation_id=:op",
                        scope + ("op" to id.invocationId)).isNotEmpty()) throw DeliveryConflict()
                throw DeliveryExpired()
            }
            if (row.string("invocation_id") != id.invocationId || row.string("digest") != id.requestDigest ||
                row.long("expires_at") != expiresAt) throw DeliveryConflict()
            if (inserted.isNotEmpty()) effect()
            DeliveryReceipt(id, row.string("rid"), "SUCCEEDED", 1)
        }
    }
}
