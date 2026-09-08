# Static files

Two entry points, both in `neton-http`:

- `staticFiles(urlPrefix, directory)` mounts a directory.
- `HttpContext.sendFile(path)` sends one file the application chose.

Both share one implementation of file reading, content type, conditional
requests and ranges, so they answer identically.

## Mounting a directory

Works the same as plain code or inside `routing { }`, because both run with a
`RequestEngine` receiver:

```kotlin
routing {
    staticFiles("/assets", "./public")
}
```

`GET /assets/css/theme.css` serves `./public/css/theme.css`. With options:

```kotlin
routing {
    staticFiles("/assets", "./public") {
        indexFile = "index.html"   // served for the mount root or a directory; null disables
        precompressed = true       // serve .br/.gz siblings by Accept-Encoding
        cacheControl = "public, max-age=3600"
    }
}
```

The mount registers catch-all `GET` and `HEAD` routes at `"/assets/{path...}"`.
A more specific route on the same prefix still wins, so a mount never shadows a
real handler.

## Sending one file

```kotlin
routes.get("/manual") { ctx ->
    // after whatever auth or business checks the route needs
    ctx.sendFile("./documents/manual.pdf")
}
```

The path is the application's, not request input, so there is no directory-escape
check on it. Do not concatenate unvalidated user input into it.

## Behaviour

- **Methods**: `GET` and `HEAD`. Anything else is `405` with `Allow: GET, HEAD`.
- **Content-Type** from the file extension; `application/octet-stream` when unknown.
- **ETag** from size and mtime. `If-None-Match` returns `304`.
- **Range**: a single range returns `206` with `Content-Range`; `If-Range` is
  honoured against the ETag; an unsatisfiable range returns `416`. Multi-range is
  not supported.
- **Pre-compressed**: with `precompressed = true`, a `.br` or `.gz` sibling is
  served when the client advertises it, with the original file's `Content-Type`,
  the matching `Content-Encoding`, and `Vary: Accept-Encoding`. Brotli is
  preferred over gzip. Serving an existing variant is a file read, not
  compression by hand.

## Security

- The request remainder is resolved against the mount root and the resolved real
  path must stay inside it, so `..` and symlinks cannot escape. Encoded `..` is
  refused too.
- Dot files (names beginning with `.`) are refused.
- There is no directory listing.

## Caching and following the disk

Small files are held in a bounded, copy-on-write cache: total bytes, entry count
and per-file size are all capped, and a file over the per-file cap is streamed
from disk on every request rather than cached. Every request re-stats the file;
a cached entry is used only while size and mtime still match. This is a
validation cache, not a TTL.

**Consistency boundary, stated plainly.** A cached file's identity is
`(size, mtime-in-milliseconds)`. The supported update path is atomic replace
(write a temp file, then rename), which always moves mtime, so the next request
sees the new bytes. A sub-millisecond in-place overwrite that keeps the exact
byte length is the one case that can be missed. The framework does not promise
"any change is seen on the next request"; it promises that an atomic replace is.
