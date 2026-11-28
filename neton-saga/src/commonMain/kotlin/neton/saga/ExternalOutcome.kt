package neton.saga

/** A query port must read a trusted authority. Missing, unavailable and mismatched views stay unknown. */
fun interface ExternalOutcomeQuery<Operation, Evidence> {
    suspend fun query(operation: Operation): Evidence?
}

class ExternalReconciler<Operation, Evidence, Result>(
    private val query: ExternalOutcomeQuery<Operation, Evidence>,
    private val bind: suspend (Operation, Evidence) -> Result?,
) {
    suspend fun resolve(operation: Operation): Result? =
        query.query(operation)?.let { bind(operation, it) }
}

data class CompensationTransfer(
    val id: String, val plan: String, val source: String, val target: String,
    val generation: Int, val budget: Int, val dueAt: String, val payload: String,
)
