package neton.http.engine.default.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neton.http.client.HttpClientError
import neton.http.client.HttpClientException
import neton.io.net.runReactor
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * A neton-io reactor on a thread of its own, owned by one outbound client.
 *
 * com.netonstream:http's pooling client, its connections and the bodies it hands out belong to the
 * reactor that created them and must be used on its thread; the caller of an [neton.http.client.HttpClient]
 * may be on any thread (a server handler on Dispatchers.Default, a `runBlocking` in a test, another
 * reactor). Every operation therefore runs as a child coroutine of this reactor ([run] / [launch]) and
 * hands its result back through a Deferred or a Channel, both thread-safe.
 *
 * Children rather than `withContext(reactor)`: a coroutine that is not in the reactor's scope would be
 * left parked when the reactor stops. As children they are cancelled first, so [stop] never strands a
 * caller: it sees "HttpClient is closed".
 */
@OptIn(ObsoleteWorkersApi::class, ExperimentalAtomicApi::class)
internal class ClientReactor<S : Any>(
    name: String,
    /** Builds the per-reactor state (the pool) on the reactor thread; [root] is the reactor's scope. */
    private val setUp: (root: CoroutineScope) -> S,
    /** Releases that state on the reactor thread when the reactor stops. */
    private val tearDown: (S) -> Unit,
    /** What a caller sees when the reactor is stopped, or stops under its operation. */
    private val closedError: (cause: Throwable?) -> Throwable = { closedException(it) },
    /** What a caller sees when the reactor failed to start ([setUp] or the reactor itself threw). */
    private val startError: (cause: Throwable) -> Throwable = { e ->
        e as? HttpClientException
            ?: HttpClientException(HttpClientError.Unknown("HTTP client reactor failed to start: ${e.message ?: e}", e))
    },
) {
    /** Completes [ready] once the reactor has stopped, so later callers fail fast with [closedError]. */
    private class Stopped : Exception("reactor stopped")

    private class Loop<S>(val state: S, val operations: CoroutineScope)

    private val ready = CompletableDeferred<Loop<S>>()
    private val stopRequested = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Unit>()
    private val stopping = AtomicBoolean(false)
    private val worker = Worker.start(name = name)

    init {
        worker.execute(TransferMode.SAFE, { this }) { it.runOnThisThread() }
    }

    private fun runOnThisThread() {
        try {
            runReactor {
                val operations = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                val state = try {
                    setUp(this)
                } catch (e: Throwable) {
                    ready.completeExceptionally(e)
                    return@runReactor
                }
                ready.complete(Loop(state, operations))
                stopRequested.await()
                // Callers first (each sees "closed"), then the connections.
                operations.coroutineContext[Job]!!.cancelAndJoin()
                tearDown(state)
            }
        } catch (e: Throwable) {
            ready.completeExceptionally(e)
        } finally {
            ready.completeExceptionally(Stopped())
            stopped.complete(Unit)
            worker.requestTermination(processScheduledJobs = false)
        }
    }

    /**
     * Runs [block] on the reactor with the state and returns its result. Cancelling the caller cancels
     * the block; the reactor stopping under it surfaces as [HttpClientException] ("closed"), not as a
     * CancellationException the caller did not ask for.
     */
    suspend fun <T> run(block: suspend CoroutineScope.(S) -> T): T {
        val loop = awaitLoop()
        val deferred = try {
            loop.operations.async(start = CoroutineStart.DEFAULT) { block(loop.state) }
        } catch (e: IllegalStateException) {
            // ReactorStoppedException: the reactor refused the dispatch, it is stopping.
            throw closedError(e)
        }
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            if (!currentCoroutineContext().isActive) throw e
            throw closedError(e)
        }
    }

    /** Starts [block] on the reactor without waiting for it; the caller owns the returned job. */
    suspend fun launch(block: suspend CoroutineScope.(S) -> Unit): Job {
        val loop = awaitLoop()
        return try {
            loop.operations.launch { block(loop.state) }
        } catch (e: IllegalStateException) {
            throw closedError(e)
        }
    }

    private suspend fun awaitLoop(): Loop<S> {
        if (stopping.load()) throw closedError(null)
        return try {
            ready.await()
        } catch (e: Stopped) {
            throw closedError(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw startError(e)
        }
    }

    /** Stops the reactor and waits for its thread to finish. Idempotent. */
    suspend fun stop() {
        stopping.store(true)
        stopRequested.complete(Unit)
        stopped.await()
    }
}

internal fun closedException(cause: Throwable? = null) =
    HttpClientException(HttpClientError.Unknown("HttpClient is closed", cause))
