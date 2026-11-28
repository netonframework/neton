package neton.core.config

/** A source is scoped to one service and environment. IDs identify publications, not content. */
interface ExternalConfigSource {
    /** Read the authoritative pointer, without falling back to a stale or cached value. */
    suspend fun currentRelease(): String

    /** Fetch and verify the complete immutable document group for this release. */
    suspend fun readRelease(release: String): Map<String, String>
}
