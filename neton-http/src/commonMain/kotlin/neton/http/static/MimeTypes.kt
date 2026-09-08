package neton.http.static

/** Extension → Content-Type, covering the arena static set and the common web types. */
internal object MimeTypes {
    private val byExtension = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "pdf" to "application/pdf",
        "wasm" to "application/wasm",
        "map" to "application/json",
        "csv" to "text/csv; charset=utf-8",
    )

    private const val DEFAULT = "application/octet-stream"

    fun forPath(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return DEFAULT
        val ext = path.substring(dot + 1).lowercase()
        return byExtension[ext] ?: DEFAULT
    }
}
