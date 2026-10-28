package compatibility

import neton.core.http.Ctx
import neton.core.security.SecurityContext
import neton.database.api.DbContext
import neton.database.api.Table
import neton.http.client.HttpClientConfig
import neton.http.mail.SmtpAccount
import neton.core.event.DomainEvent
import neton.core.event.DomainEventCodec
import neton.redis.RedisClient
import neton.redis.setTyped

// These consumer spellings are fixtures, not generated from current signatures.
fun user(context: Ctx): String? = SecurityContext(context = context).userId()
fun clientOptions() = HttpClientConfig().apply { connectMillis = 1000; requestMillis = 2000 }
fun mail() = SmtpAccount(host = "localhost", port = 465, username = "test", password = "test", ssl = true, starttls = false)
suspend fun query(db: DbContext) = db.fetchAll(sql = "SELECT 1")
suspend fun <T : Any> get(table: Table<T, Long>, id: Long) = table.get(id = id)

data class User(val id: Long, val name: String)
suspend fun rename(table: Table<User, Long>) = table.query { }.update { set(User::name, "updated") }

// Existing stateless codecs remain source-compatible with the default seal hook.
class StatelessCodec : DomainEventCodec {
    override fun encode(event: DomainEvent): String? = null
    override fun decode(eventType: String, payload: String): DomainEvent? = null
}
suspend fun typedRedis(client: RedisClient) = client.setTyped(key = "test", value = 42)

fun storageOptions() = neton.storage.StorageConfig().apply {
    val configure: neton.storage.SourceOverrides.() -> Unit = { pathStyle = false; baseUrl = "" }
    source(name = "default", block = configure)
}
fun completeStorageOptions() = neton.storage.StorageConfig(
    sources = mutableListOf(neton.storage.SourceConfig(basePath = "./uploads"))
).copy()
