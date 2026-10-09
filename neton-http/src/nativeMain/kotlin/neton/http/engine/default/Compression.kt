@file:OptIn(ExperimentalForeignApi::class)

package neton.http.engine.default

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream

/** Bodies below this are not worth gzip's CPU and header overhead (hyper4k's threshold). */
internal const val MIN_COMPRESS_BYTES = 256

/**
 * gzip [src] with zlib's default level (hyper4k uses flate2's default, the same level 6); null when
 * [src] is empty or zlib fails. The output buffer is `deflateBound`, so one `deflate(Z_FINISH)`
 * always completes.
 */
@OptIn(UnsafeNumber::class)
internal fun gzip(src: ByteArray): ByteArray? {
    if (src.isEmpty()) return null
    return memScoped {
        val stream = alloc<z_stream>()
        // windowBits 15 + 16: a gzip wrapper instead of zlib's.
        val init = deflateInit2_(
            stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY,
            ZLIB_VERSION, sizeOf<z_stream>().convert(),
        )
        if (init != Z_OK) return@memScoped null
        try {
            // deflateBound covers the zlib wrapper; the gzip header and trailer are 18 bytes, add them.
            // zlib's uLong has platform-dependent width; check before narrowing to array size.
            val bound = deflateBound(stream.ptr, src.size.convert()).convert<Long>() + 18
            if (bound > Int.MAX_VALUE || bound <= 0) return@memScoped null
            val capacity = bound.toInt()
            val dst = ByteArray(capacity)
            val rc = src.usePinned { s ->
                dst.usePinned { d ->
                    stream.next_in = s.addressOf(0).reinterpret()
                    stream.avail_in = src.size.convert()
                    stream.next_out = d.addressOf(0).reinterpret()
                    stream.avail_out = capacity.convert()
                    deflate(stream.ptr, Z_FINISH)
                }
            }
            if (rc != Z_STREAM_END) return@memScoped null
            dst.copyOf(stream.total_out.convert<Int>())
        } finally {
            deflateEnd(stream.ptr)
        }
    }
}

/**
 * hyper4k's `maybeCompress`, unchanged in policy: gzip the body when the client asked for it and
 * the payload is worth it — compression enabled, a buffered (not streamed) body of at least
 * [MIN_COMPRESS_BYTES], not a 206, no Content-Encoding yet, no `Cache-Control: no-transform`,
 * Accept-Encoding offering gzip with a non-zero q-value, a compressible Content-Type, and a
 * result smaller than the input. Otherwise the response is returned untouched, so a request
 * without Accept-Encoding never gets a Content-Encoding header.
 */
internal fun maybeCompress(enabled: Boolean, acceptEncoding: String?, resp: EngineResponse): EngineResponse {
    if (!enabled) return resp
    if (resp.isStreamed || resp.body.size < MIN_COMPRESS_BYTES) return resp
    if (resp.status == 206) return resp
    val h = resp.headers
    if (h.keys.any { it.equals("Content-Encoding", ignoreCase = true) }) return resp
    if (headerValues(h, "Cache-Control").any { it.contains("no-transform", ignoreCase = true) }) return resp
    if (!acceptsGzip(acceptEncoding)) return resp
    val contentType = headerValues(h, "Content-Type").firstOrNull()
    if (contentType == null || !isCompressibleType(contentType)) return resp
    val gz = gzip(resp.body) ?: return resp
    if (gz.size >= resp.body.size) return resp
    val headers = LinkedHashMap<String, List<String>>(h.size + 2)
    for ((k, v) in h) {
        if (k.equals("Content-Length", ignoreCase = true)) continue
        if (k.equals("Vary", ignoreCase = true)) continue
        headers[k] = v
    }
    headers["Content-Encoding"] = listOf("gzip")
    // Merge into an existing Vary (e.g. Origin) rather than clobbering it.
    val priorVary = headerValues(h, "Vary")
    headers["Vary"] = if (priorVary.any { it.contains("Accept-Encoding", ignoreCase = true) }) {
        priorVary
    } else {
        priorVary + "Accept-Encoding"
    }
    return EngineResponse(resp.status, headers, gz)
}

private fun headerValues(h: Map<String, List<String>>, name: String): List<String> =
    h.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()

/** True only if Accept-Encoding offers gzip (or `*`) with a q-value other than 0. */
internal fun acceptsGzip(acceptEncoding: String?): Boolean {
    if (acceptEncoding == null) return false
    for (part in acceptEncoding.split(',')) {
        val token = part.trim()
        val coding = token.substringBefore(';').trim()
        if (!coding.equals("gzip", ignoreCase = true) && coding != "*") continue
        val q = token.substringAfter(";", "").split(';')
            .firstOrNull { it.trim().startsWith("q=", ignoreCase = true) }
            ?.substringAfter("=")?.trim()?.toDoubleOrNull()
        if (q == null || q > 0.0) return true
    }
    return false
}

private fun isCompressibleType(contentType: String): Boolean {
    val ct = contentType.substringBefore(';').trim().lowercase()
    return ct.startsWith("application/json") ||
        ct.startsWith("text/") ||
        ct == "application/javascript" ||
        ct == "application/xml" ||
        ct.endsWith("+json") ||
        ct.endsWith("+xml")
}
