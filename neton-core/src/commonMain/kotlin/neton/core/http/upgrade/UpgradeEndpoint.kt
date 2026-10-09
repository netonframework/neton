package neton.core.http.upgrade

import kotlinx.coroutines.Deferred
import neton.core.component.NetonContext
import neton.core.http.HandlerArgs
import neton.core.http.HttpContext

/** Protocol-neutral endpoint, invoked only after HTTP authentication and rate limiting. */
interface UpgradeEndpoint {
    fun validate(context: NetonContext)
    suspend fun decide(context: HttpContext, args: HandlerArgs): UpgradeDecision
}

sealed interface UpgradeDecision {
    class Reject(val status: Int, val headers: Map<String, List<String>> = emptyMap()) : UpgradeDecision

    /**
     * The adapter owns this ticket until [run] returns. [release] is called in all paths,
     * including failed writes of 101 and cancellation before handoff, and must be idempotent.
     * [run] executes on the upgraded transport's executor and must observe [Deferred] shutdown.
     */
    interface Accept : UpgradeDecision {
        val headers: Map<String, List<String>>
        suspend fun run(connection: UpgradedConnection, shutdown: Deferred<Unit>)
        fun release()
    }
}
