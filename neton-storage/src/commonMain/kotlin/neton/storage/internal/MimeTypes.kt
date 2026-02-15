package neton.storage.internal

private val MIME_MAP = mapOf(
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "js" to "application/javascript",
    "mjs" to "application/javascript",
    "json" to "application/json",
    "xml" to "application/xml",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "md" to "text/markdown",
    "yaml" to "text/yaml",
    "yml" to "text/yaml",
    "toml" to "application/toml",

    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "ico" to "image/x-icon",
    "avif" to "image/avif",

    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "flac" to "audio/flac",

    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "avi" to "video/x-msvideo",
    "mov" to "video/quicktime",

    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "gz" to "application/gzip",
    "tar" to "application/x-tar",
    "7z" to "application/x-7z-compressed",
    "rar" to "application/vnd.rar",

    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "ttf" to "font/ttf",
    "otf" to "font/otf",

    "wasm" to "application/wasm",
    "bin" to "application/octet-stream"
)

internal fun guessMimeType(path: String): String? {
    val dot = path.lastIndexOf('.')
    if (dot < 0) return null
    val ext = path.substring(dot + 1).lowercase()
    return MIME_MAP[ext]
}
