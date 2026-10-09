package neton.http.engine.default

import kotlinx.coroutines.CoroutineDispatcher
import neton.core.http.upgrade.UpgradedConnection
import neton.io.core.IoStream
import kotlin.reflect.KClass

/** Wrap only an already-upgraded stream, retaining the HTTP parser's unread prefix. */
class DefaultUpgradedConnection(
    private val stream: IoStream,
    override val executor: CoroutineDispatcher,
) : UpgradedConnection {
    private var closed = false

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> unwrap(type: KClass<T>): T? =
        if (!closed && type == IoStream::class) stream as T else null

    override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }
}
