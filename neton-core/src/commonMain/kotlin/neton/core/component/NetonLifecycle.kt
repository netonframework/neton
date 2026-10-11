package neton.core.component

/**
 * Runtime resource with application-owned activation and shutdown.
 * Each stop has a 30-second cooperative deadline during application cleanup.
 * Implementations must not block threads indefinitely or swallow cancellation.
 */
interface NetonLifecycle {
    suspend fun start()
    suspend fun stop()
}

/** Startup registry for module-owned clients, workers, schedulers and coroutine scopes. */
class LifecycleRegistry internal constructor(
    private val requireRegistrationOpen: () -> Unit,
) {
    private data class Entry(val name: String, val lifecycle: NetonLifecycle)

    private val entries = mutableListOf<Entry>()
    private val names = mutableSetOf<String>()
    private val started = mutableListOf<Entry>()

    fun register(name: String, lifecycle: NetonLifecycle) {
        requireRegistrationOpen()
        require(name.isNotBlank()) { "Lifecycle name must not be blank" }
        check(names.add(name)) { "Lifecycle '$name' is already registered" }
        entries += Entry(name, lifecycle)
    }

    internal suspend fun startAll() {
        for (entry in entries) {
            entry.lifecycle.start()
            started += entry
        }
    }

    internal suspend fun stopStarted(onFailure: (String, Throwable) -> Unit) {
        for (entry in started.asReversed()) {
            try {
                kotlinx.coroutines.withTimeout(30_000) { entry.lifecycle.stop() }
            } catch (error: Throwable) {
                onFailure(entry.name, error)
            }
        }
        started.clear()
    }

    internal fun registeredNames(): List<String> = entries.map { it.name }
}

/** Lifecycle state exposed for diagnostics and contract tests. */
enum class NetonLifecycleState {
    CREATED,
    REGISTERING,
    VALIDATING,
    FROZEN,
    STARTING,
    READY,
    STOPPING,
    STOPPED,
}
