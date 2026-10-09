package neton.core.http.upgrade

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.reflect.KClass

/**
 * A transport after HTTP has completed its protocol switch, including any unread prefix.
 * All operations are confined to [executor]. The receiving protocol owns cleanup after a
 * successful handoff; before that the HTTP adapter owns it. No WebSocket types belong here.
 */
interface UpgradedConnection {
    val executor: CoroutineDispatcher
    fun <T : Any> unwrap(type: KClass<T>): T?
    /** Idempotent transport close. The HTTP connection owner releases its TCP slot separately. */
    fun close()
}
