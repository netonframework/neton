package neton.http.static

/** One stat of a file: enough to detect a change and to frame a response. */
internal class FileStat(
    val size: Long,
    /** Modification time in epoch millis; 0 when the platform cannot report it. */
    val mtimeMillis: Long,
    val isDirectory: Boolean,
)

/**
 * The filesystem primitives the static handler needs, kept deliberately small so
 * neton-http need not depend on neton-storage. Each is a thin platform call.
 */
internal expect object StaticFileSystem {
    /** Stat a path, or null when it does not exist or cannot be read. */
    fun stat(path: String): FileStat?

    /** Read a whole file, or null on any error. */
    fun readAll(path: String): ByteArray?

    /**
     * Read [length] bytes starting at [offset]. Returns the bytes actually read
     * (may be shorter at EOF), or null on error. Used for Range responses without
     * pulling the whole file into memory.
     */
    fun readRange(path: String, offset: Long, length: Long): ByteArray?

    /** Canonicalise a path, resolving `.`/`..`/symlinks; null if it cannot. */
    fun realPath(path: String): String?
}
