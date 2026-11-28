package neton.core.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.core.CoreLog
import kotlin.concurrent.AtomicReference

/** Immutable group view: capture once, then keep it for the whole operation. */
class ExternalConfigSnapshot internal constructor(
    val release: String,
    private val values: Map<String, Map<String, Any?>>,
) {
    fun module(name: String): Map<String, Any?> = copyTable(values[name].orEmpty())
}

private fun copyTable(table: Map<String, Any?>): Map<String, Any?> = table.mapValues { copyValue(it.value) }

private fun copyValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { it.key to copyValue(it.value) }
    is List<*> -> value.map(::copyValue)
    else -> value
}

class ExternalConfig internal constructor(
    private val source: ExternalConfigSource,
    args: Array<String>,
    private val hotFiles: Set<String>,
    private val validate: (ExternalConfigSnapshot) -> Unit,
    private val pollMillis: Long,
    private val retryMillis: Long,
) {
    private val env = ConfigLoader.resolveEnvironment(args)
    private val args = args.copyOf()
    private val environment = getEnvMap().toMap()
    private val state = AtomicReference<ExternalConfigSnapshot?>(null)
    private var worker: Job? = null
    private lateinit var startupFiles: Map<String, String>
    private lateinit var localFiles: Map<String, String?>
    private var activeFiles: Map<String, String> = emptyMap()
    private var attempt = 0L

    fun snapshot(): ExternalConfigSnapshot = checkNotNull(state.value)

    internal suspend fun initialize(): Map<String, String?> {
        val initial = try {
            load("startup")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: error("Required external configuration unavailable or invalid")
        startupFiles = initial.files
        activeFiles = initial.files
        state.value = initial.snapshot
        return localFiles + startupFiles
    }

    internal fun start(scope: CoroutineScope) {
        check(worker == null)
        worker = scope.launch {
            var retry = false
            var backoff = retryMillis
            // A single owner awaits reads, cleanup and delay. There is no detached timer callback.
            while (true) {
                if (retry) event("retry.scheduled", attempt + 1)
                delay(if (retry) backoff else pollMillis)
                currentCoroutineContext().ensureActive()
                val trigger = if (retry) "retry" else "poll"
                val id = attempt + 1
                try {
                    val candidate = load(trigger)
                    currentCoroutineContext().ensureActive()
                    if (candidate != null) {
                        if (candidate.files.any { (key, value) -> key !in hotFiles && activeFiles[key] != value }) {
                            event("pending_restart", attempt, candidate.snapshot.release)
                        } else {
                            activeFiles = candidate.files
                            state.value = candidate.snapshot
                            event("applied", attempt, candidate.snapshot.release)
                        }
                    }
                    if (retry) event("retry.recovered", attempt, snapshot().release)
                    retry = false
                    backoff = retryMillis
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    event("rejected", attempt, snapshot().release)
                    if (retry) backoff = minOf(backoff * 2, 30_000)
                    retry = true
                } finally {
                    event("attempt.done", id)
                }
            }
        }
    }

    internal suspend fun stop() {
        worker?.cancelAndJoin()
    }

    private suspend fun load(trigger: String): Candidate? = withTimeoutOrNull(15_000) {
        val id = ++attempt
        event("read.$trigger", id)
        var release = source.currentRelease()
        // Bound consecutive supersessions without waiting for a new publication notification.
        repeat(8) {
            require(RELEASE.matches(release))
            if (release == state.value?.release) return@withTimeoutOrNull LoadResult(null)
            event("observed", id, release)
            val files = source.readRelease(release).toMap()
            require(files.isNotEmpty() && files.keys.all { FILE.matches(it) })
            if (::startupFiles.isInitialized) require(files.keys == startupFiles.keys)
            if (!::localFiles.isInitialized || !::startupFiles.isInitialized) {
                localFiles = captureLocal(files.keys)
            }
            val snapshot = prepare(release, files)
            validate(snapshot)
            event("recheck", id, release)
            val confirmed = source.currentRelease()
            if (confirmed == release) return@withTimeoutOrNull LoadResult(Candidate(files, snapshot))
            release = confirmed
        }
        error("External configuration pointer changed repeatedly")
    }.let { result -> (result ?: error("External configuration read timed out")).candidate }

    private fun captureLocal(files: Set<String>): Map<String, String?> =
        files.map { it.substringBefore('.') }.distinct().flatMap { name ->
            listOf("$name.conf", "$name.$env.conf")
        }.filterNot(files::contains).associateWith { readConfigFile("config/$it") }

    private fun prepare(release: String, files: Map<String, String>): ExternalConfigSnapshot {
        val parsed = (localFiles + files).mapValues { (name, text) -> text?.let { TomlParser.parse(it, name) } }
        val values = files.keys.map { it.substringBefore('.') }.distinct().associateWith { name ->
            val base = parsed["$name.conf"].orEmpty()
            val merged = ConfigMerge.merge(base, parsed["$name.$env.conf"].orEmpty()).toMutableMap()
            if (name == "application") ConfigOverrides.applyOverrides(merged, environment, args)
            else ConfigOverrides.applyModuleOverrides(name, merged, environment, args)
        }
        return ExternalConfigSnapshot(release, values)
    }

    private fun event(kind: String, id: Long, release: String? = null) {
        val log = CoreLog.logOrBootstrap()
        val message = "neton.config.external.$kind"
        val fields = mapOf("attempt" to id, "release" to release)
        when (kind) {
            "read.poll", "observed", "recheck", "attempt.done" -> log.debug(message, fields)
            "rejected" -> log.warn(message, fields)
            else -> log.info(message, fields)
        }
    }

    private data class Candidate(val files: Map<String, String>, val snapshot: ExternalConfigSnapshot)
    private data class LoadResult(val candidate: Candidate?)

    private companion object {
        val FILE = Regex("[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9_-]{1,32})?\\.conf")
        val RELEASE = Regex("[A-Za-z0-9_-]{1,96}")
    }
}
