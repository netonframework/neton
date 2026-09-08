package neton.http.static

import neton.core.http.HttpContext
import neton.core.http.HttpMethod
import neton.core.http.HttpStatus

/** Tuning for a [StaticFileServer] mount. */
class StaticFilesConfig internal constructor() {
    /** File served when the request resolves to the mount root or a directory. */
    var indexFile: String? = "index.html"

    /**
     * Serve pre-built `.br`/`.gz` siblings when the client advertises them. This
     * is a file read, not compression, which is why it is allowed where hand
     * compression is not: the bytes already exist on disk.
     */
    var precompressed: Boolean = false

    /** Cache-Control for served files. Null omits the header. */
    var cacheControl: String? = null
}

/**
 * Serves a directory through the framework's own file handling: a stat, a read,
 * and HTTP framing. No cache is assembled in the caller and the directory is not
 * read into a map at startup, so the mount follows the disk.
 *
 * Security: the request remainder is resolved against the root and the resolved
 * real path must stay inside it, so `..` and symlinks cannot escape. Dot files
 * and directory listings are refused.
 */
internal class StaticFileServer(
    root: String,
    private val config: StaticFilesConfig,
) {
    // Canonical root with a trailing slash, so a prefix check cannot be fooled by
    // a sibling dir sharing a name prefix (/data vs /data-evil).
    private val canonicalRoot: String = (StaticFileSystem.realPath(root) ?: root).trimEnd('/') + "/"
    private val cache = StaticFileCache()

    suspend fun serve(context: HttpContext, relativePath: String) {
        val method = context.request.method
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            context.response.status = HttpStatus.METHOD_NOT_ALLOWED
            context.response.header("Allow", "GET, HEAD")
            context.response.write(ByteArray(0))
            return
        }

        if (!isSafeRelative(relativePath)) {
            notFound(context); return
        }

        var target = canonicalRoot + relativePath.trimStart('/')
        var stat = StaticFileSystem.stat(target)

        // Directory (or the mount root) resolves to the index file, if configured.
        if (stat == null || stat.isDirectory) {
            val index = config.indexFile
            if (index != null) {
                val withIndex = target.trimEnd('/') + "/" + index
                val indexStat = StaticFileSystem.stat(withIndex)
                if (indexStat != null && !indexStat.isDirectory) {
                    target = withIndex; stat = indexStat
                } else { notFound(context); return }
            } else { notFound(context); return }
        }

        // The resolved real path must sit inside the root.
        val real = StaticFileSystem.realPath(target)
        if (real == null || !isInsideRoot(real)) {
            notFound(context); return
        }
        val fileStat = stat!!

        // Pre-compressed sibling selection off Accept-Encoding, when enabled.
        var servePath = target
        var serveStat = fileStat
        var contentEncoding: String? = null
        val baseContentType = MimeTypes.forPath(target)
        if (config.precompressed && method != HttpMethod.HEAD) {
            val enc = pickEncoding(context.request.header("Accept-Encoding"))
            if (enc != null) {
                val variant = target + "." + enc.second
                val vStat = StaticFileSystem.stat(variant)
                if (vStat != null && !vStat.isDirectory) {
                    val vReal = StaticFileSystem.realPath(variant)
                    if (vReal != null && isInsideRoot(vReal)) {
                        servePath = variant; serveStat = vStat; contentEncoding = enc.first
                    }
                }
            }
        }

        respondWithFile(context, servePath, serveStat, baseContentType, contentEncoding, config.cacheControl, cache)
    }

    private fun isInsideRoot(real: String): Boolean {
        val normalized = real.trimEnd('/')
        return normalized == canonicalRoot.trimEnd('/') || (normalized + "/").startsWith(canonicalRoot)
    }

    private fun isSafeRelative(path: String): Boolean {
        if (path.isEmpty()) return true
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            if (segment == "." || segment == "..") return false
            if (segment.startsWith(".")) return false
        }
        return true
    }
}

/**
 * 404 with a bare body, not the JSON error envelope. Writing commits the
 * response — a static miss that only set the status would be overwritten by the
 * dispatcher's success envelope, since a response counts as committed only once
 * its body is set.
 */
private suspend fun notFound(context: HttpContext) {
    context.response.status = HttpStatus.NOT_FOUND
    context.response.contentType = "text/plain; charset=utf-8"
    context.response.write("Not Found".encodeToByteArray())
}

/** Returns (Content-Encoding value, file extension) for the best offered encoding. */
private fun pickEncoding(acceptEncoding: String?): Pair<String, String>? {
    if (acceptEncoding.isNullOrBlank()) return null
    val accepted = acceptEncoding.split(',').map { it.trim().substringBefore(';').lowercase() }
    if ("br" in accepted) return "br" to "br"
    if ("gzip" in accepted) return "gzip" to "gz"
    return null
}

private fun makeEtag(stat: FileStat, encoding: String?): String {
    val suffix = if (encoding != null) "-" + encoding else ""
    return "\"" + stat.size.toString(16) + "-" + stat.mtimeMillis.toString(16) + suffix + "\""
}

private fun etagMatches(headerValue: String, etag: String): Boolean {
    if (headerValue.trim() == "*") return true
    return headerValue.split(',').any { it.trim().removePrefix("W/") == etag }
}

private fun parseSingleRange(header: String, size: Long): Pair<Long, Long>? {
    if (!header.startsWith("bytes=")) return null
    val spec = header.substring(6)
    if (',' in spec) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startStr = spec.substring(0, dash).trim()
    val endStr = spec.substring(dash + 1).trim()
    return when {
        startStr.isEmpty() -> {
            val n = endStr.toLongOrNull() ?: return null
            if (n <= 0) return null
            maxOf(0L, size - n) to (size - 1)
        }
        endStr.isEmpty() -> {
            val start = startStr.toLongOrNull() ?: return null
            if (start >= size) return null
            start to (size - 1)
        }
        else -> {
            val start = startStr.toLongOrNull() ?: return null
            val end = endStr.toLongOrNull() ?: return null
            if (start > end || start >= size) return null
            start to minOf(end, size - 1)
        }
    }
}

/**
 * Frames a resolved file into the response: ETag, Accept-Ranges, conditional
 * (If-None-Match), HEAD, single Range (with If-Range), else the full body. Shared
 * by directory mounts and [sendFile] so both answer the same way. When [cache] is
 * given, small files are served from it; otherwise every read goes to disk.
 */
internal suspend fun respondWithFile(
    context: HttpContext,
    servePath: String,
    serveStat: FileStat,
    contentType: String,
    contentEncoding: String?,
    cacheControl: String?,
    cache: StaticFileCache?,
) {
    val method = context.request.method
    val etag = makeEtag(serveStat, contentEncoding)
    context.response.contentType = contentType
    context.response.header("ETag", etag)
    context.response.header("Accept-Ranges", "bytes")
    if (contentEncoding != null) {
        context.response.header("Content-Encoding", contentEncoding)
        context.response.header("Vary", "Accept-Encoding")
    }
    cacheControl?.let { context.response.header("Cache-Control", it) }

    val inm = context.request.header("If-None-Match")
    if (inm != null && etagMatches(inm, etag)) {
        context.response.status = HttpStatus.NOT_MODIFIED
        context.response.write(ByteArray(0))
        return
    }

    if (method == HttpMethod.HEAD) {
        context.response.header("Content-Length", serveStat.size.toString())
        context.response.write(ByteArray(0))
        return
    }

    val rangeHeader = context.request.header("Range")
    val ifRange = context.request.header("If-Range")
    val rangeOk = rangeHeader != null && (ifRange == null || ifRange == etag)
    if (rangeOk) {
        val range = parseSingleRange(rangeHeader!!, serveStat.size)
        if (range == null) {
            context.response.status = HttpStatus.RANGE_NOT_SATISFIABLE
            context.response.header("Content-Range", "bytes */" + serveStat.size)
            context.response.write(ByteArray(0))
            return
        }
        val start = range.first
        val end = range.second
        val bytes = StaticFileSystem.readRange(servePath, start, end - start + 1)
            ?: run { notFound(context); return }
        context.response.status = HttpStatus.PARTIAL_CONTENT
        context.response.header("Content-Range", "bytes " + start + "-" + end + "/" + serveStat.size)
        context.response.header("Content-Length", bytes.size.toString())
        context.response.write(bytes)
        return
    }

    val bytes = (cache?.get(servePath, serveStat) ?: StaticFileSystem.readAll(servePath))
        ?: run { notFound(context); return }
    context.response.header("Content-Length", bytes.size.toString())
    context.response.write(bytes)
}

/**
 * Sends a single file the application chose, with the same conditional/Range/HEAD
 * handling as a directory mount. The path is trusted — it comes from the app, not
 * from unvalidated request input — so there is no directory-escape check here;
 * callers must not concatenate raw user input into it. Responds 404 if the file
 * is absent or a directory.
 */
suspend fun HttpContext.sendFile(path: String, contentType: String? = null) {
    val stat = StaticFileSystem.stat(path)
    if (stat == null || stat.isDirectory) {
        notFound(this); return
    }
    respondWithFile(
        context = this,
        servePath = path,
        serveStat = stat,
        contentType = contentType ?: MimeTypes.forPath(path),
        contentEncoding = null,
        cacheControl = null,
        cache = null,
    )
}
