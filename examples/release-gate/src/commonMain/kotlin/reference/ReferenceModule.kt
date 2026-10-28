package reference

import kotlinx.serialization.Serializable
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Module
import neton.core.component.NetonContext
import neton.core.component.NetonLifecycleState
import neton.database.api.DbContext

@Module(id = "reference", migrations = true)
object ReferenceModule

@Serializable
data class Health(val status: String, val value: String)

@Controller("/health")
class HealthController(private val db: DbContext, private val context: NetonContext) {
    @Get
    suspend fun health(): Health {
        check(context.lifecycleState == NetonLifecycleState.READY)
        val row = db.fetchAll("SELECT value FROM reference_probe WHERE id = :id", mapOf("id" to 1L)).single()
        return Health("READY", row.string("value"))
    }
}
