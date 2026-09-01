package neton.core.http

/**
 * Immutable, ordered, multi-value HTTP headers.
 *
 * A `Map<String, String>` cannot express what HTTP actually allows: repeated
 * `Set-Cookie`, repeated `Via` or `Warning`, and HTTP/2 fields that legitimately
 * appear more than once. Collapsing them to one value per name loses data
 * silently, and single-valued fixtures never reveal it.
 *
 * Names keep the casing they were given, because some peers are picky about it,
 * while every lookup is case-insensitive as the protocol requires. Order is
 * preserved within a name; that matters for `Set-Cookie`.
 */
class HttpHeaders private constructor(
    private val lines: List<HttpHeader>,
) : Headers {

    override fun get(name: String): String? =
        lines.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    override fun getAll(name: String): List<String> =
        lines.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

    override fun contains(name: String): Boolean =
        lines.any { it.name.equals(name, ignoreCase = true) }

    override fun names(): Set<String> = lines.mapTo(LinkedHashSet()) { it.name }

    override fun toMap(): Map<String, List<String>> =
        buildMap<String, MutableList<String>> {
            for (entry in lines) {
                val key = keys.firstOrNull { it.equals(entry.name, ignoreCase = true) } ?: entry.name
                getOrPut(key) { mutableListOf() }.add(entry.value)
            }
        }

    /** Every header line, in wire order. */
    fun asList(): List<HttpHeader> = lines

    val isEmpty: Boolean get() = lines.isEmpty()

    /** Appends one line. Does not replace an existing value with the same name. */
    fun add(name: String, value: String): HttpHeaders = HttpHeaders(lines + HttpHeader(name, value))

    /** Replaces every line with this name. */
    fun set(name: String, value: String): HttpHeaders =
        HttpHeaders(lines.filterNot { it.name.equals(name, ignoreCase = true) } + HttpHeader(name, value))

    fun remove(name: String): HttpHeaders =
        HttpHeaders(lines.filterNot { it.name.equals(name, ignoreCase = true) })

    override fun equals(other: Any?): Boolean = other is HttpHeaders && other.lines == lines

    override fun hashCode(): Int = lines.hashCode()

    override fun toString(): String = lines.joinToString(", ") { "${it.name}: ${it.value}" }

    companion object {
        val EMPTY: HttpHeaders = HttpHeaders(emptyList())

        fun of(vararg pairs: Pair<String, String>): HttpHeaders =
            if (pairs.isEmpty()) EMPTY else HttpHeaders(pairs.map { HttpHeader(it.first, it.second) })

        fun of(lines: List<HttpHeader>): HttpHeaders =
            if (lines.isEmpty()) EMPTY else HttpHeaders(lines.toList())

        /** Single-valued source, for callers that genuinely have one value per name. */
        fun from(values: Map<String, String>): HttpHeaders =
            if (values.isEmpty()) EMPTY else HttpHeaders(values.map { HttpHeader(it.key, it.value) })

        /** Multi-valued source, e.g. what an engine hands back. */
        fun fromMultiMap(values: Map<String, List<String>>): HttpHeaders {
            val lines = values.flatMap { (name, all) -> all.map { HttpHeader(name, it) } }
            return if (lines.isEmpty()) EMPTY else HttpHeaders(lines)
        }
    }
}

/** One header line. Repeated names are repeated lines, which is what the wire does. */
data class HttpHeader(val name: String, val value: String)
