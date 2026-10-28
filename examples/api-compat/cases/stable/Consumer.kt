package compatibility

import neton.core.http.Ctx
import neton.core.security.SecurityContext
import neton.database.api.DbContext
import neton.database.api.Table
import neton.http.client.HttpClientConfig
import neton.http.mail.SmtpAccount

// These consumer spellings are fixtures, not generated from current signatures.
fun user(context: Ctx): String? = SecurityContext(context = context).userId()
fun clientOptions() = HttpClientConfig().apply { connectMillis = 1000; requestMillis = 2000 }
fun mail() = SmtpAccount(host = "localhost", port = 465, username = "test", password = "test", ssl = true, starttls = false)
suspend fun query(db: DbContext) = db.fetchAll(sql = "SELECT 1")
suspend fun <T : Any> get(table: Table<T, Long>, id: Long) = table.get(id = id)

data class User(val id: Long, val name: String)
suspend fun rename(table: Table<User, Long>) = table.query { }.update { set(User::name, "updated") }
