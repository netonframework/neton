# neton-http-netonstream

An HTTP server engine for Neton built on our own Kotlin/Native libraries instead of Rust (hyper4k)
or Ktor:

| Library | Coordinates | Role |
|---|---|---|
| neton-io | `com.netonstream:io:0.1.0` | reactors (epoll / io_uring / kqueue / IOCP), multi-reactor TCP server |
| http | `com.netonstream:http:0.1.0` | HTTP/1.1 (hyper 1.11.1), HTTP/2 (h2 0.4.19), both on one port (hyper-util `server::conn::auto`) |
| tls | `com.netonstream:tls:0.1.0` | TLS as an `IoStream` over OpenSSL 4.0.2 (`com.netonstream:openssl:0.1.0`), ALPN |

Like every Neton engine it only moves bytes: routing, security, rate limiting, CORS, the response
envelope and logging are the framework's shared `BufferedHttpDispatcher`.

Targets: macosArm64, macosX64, linuxX64, linuxArm64, mingwX64 (Kotlin 2.4.0).

## Selecting it

The module does not ship the no-argument `http { }` — only one engine may own it, and the default
engine (hyper4k) does. Select this one explicitly:

```kotlin
import neton.http.http
import neton.http.netonstream.NetonStreamHttpAdapter

Neton.run(args) {
    http(::NetonStreamHttpAdapter) { port = 8080 }
    routing { get("/") { "hello" } }
}
```

Engine options that `HttpServerConfig` does not carry go through a lambda:

```kotlin
http({ NetonStreamHttpAdapter(it, NetonStreamOptions(reactors = 2)) }) { port = 8080 }
```

| `NetonStreamOptions` | Default | |
|---|---|---|
| `host` | `0.0.0.0` | listen address |
| `reactors` | online cores | reactor threads (Tokio's worker count in hyper4k) |
| `acceptMode` | `Handoff` | `ReusePort` spreads accepts in the kernel (Linux) |
| `maxRequestBodyBytes` | 16 MiB | hyper4k's limit; beyond → 413 |
| `streamQueueCapacity` | 8 | chunks a streaming handler may run ahead of the socket |
| `tlsHandshakeTimeoutMillis` | 10 000 | |
| `maxOpenConnections` | 16 384 | accepted TCP connections, separate from handler concurrency |
| `maxBufferedRequestBytes` | 128 MiB | active upload/handler buffer reservations; not a process RSS limit |
| `requestBodyTimeoutMillis` | 30 000 | upload deadline (408): HTTP/1 from the first wait for body bytes, HTTP/2 for the whole read; also active when handler timeout is disabled |

GC tuning is opt-in through neton-io's environment variables (`NETON_IO_GC_MIN_HEAP_MB`,
`NETON_IO_GC_THREAD_NICE`), applied when the engine starts. On Linux, `NETON_IO_DRIVER=epoll|iouring`
picks the reactor driver.

## TLS

Configured like any engine, with `HttpServerConfig.tls` (`TlsSettings(certificatePath,
privateKeyPath, alpnProtocols)`):

```kotlin
http(::NetonStreamHttpAdapter) {
    port = 8443
    tls {
        certificatePath = "certs/cert.pem"   // PEM, chain allowed
        privateKeyPath = "certs/key.pem"
        alpnProtocols = listOf("h2", "http/1.1")   // server preference, first match wins
    }
}
```

The handshake is done by `neton.tls.tlsAccept`; the negotiated protocol (`TlsStream.alpn`) is handed
to the auto server, so `h2` serves HTTP/2 and `http/1.1` HTTP/1.1 without sniffing. An empty list
advertises nothing, which leaves HTTP/1.1. An unreadable certificate or key fails `start()` with both
paths in the message, as hyper4k does.

Note (framework, every engine): `application.conf` arrays are not parsed yet, so
`alpnProtocols = ["h2", "http/1.1"]` under `[http.tls]` falls back to `http/1.1`. Use the DSL for
ALPN until the config parser reads arrays.

## Capabilities compared with hyper4k

| | hyper4k | netonstream | Evidence (tests in this module) |
|---|---|---|---|
| `ASYNC_HANDOFF` | yes | yes: handlers start inline on the reactor, continue on `Dispatchers.Default` after suspending | `NetonStreamAdapterTest.createsConfiguredAdapterWithHyper4kCapabilities`, `requestsOverMaxConnectionsAre503` (a suspended handler does not block the next connection) |
| `STREAMING_RESPONSE` | yes | yes | `NetonStreamConformanceTest.streamingReleasesChunksAsProduced`, `streamingDoesNotDeclareContentLength`, `sseOverH2DeliversEveryChunk` |
| `HTTP_2` | yes (h2c + h2 over TLS) | yes (h2c prior knowledge on the HTTP/1 port, h2 by ALPN) | `h2cPriorKnowledgeAndHttp1ShareThePort`, `tlsAlpnSelectsH2` |
| `MULTIPART` | not declared | not declared | — |
| `TRAILERS` | not declared | not declared (the framework has no trailer API) | `capabilityValidationRejectsWhatIsNotDeclared` |
| HTTP/1.1 keep-alive, pipelining | yes | yes | `http1KeepAliveServesSeveralRequestsOnOneConnection`, `http1PipelinedRequestsAreAnsweredInOrder`, `http1ConnectionCloseIsHonoured` |
| TLS + ALPN | rustls | OpenSSL 4.0.2 | `tlsAlpnSelectsH2`, `tlsAlpnSelectsHttp11`, `tlsServerThatOnlyAdvertisesHttp11ServesHttp11ToAnH2CapableClient`, `unreadableCertificateFailsStartWithThePaths` |
| Request timeout → 504 (timer armed only if the handler suspends; `timeout = 0` disables) | yes | same | `handlerOverTheTimeoutIs504` |
| More than `maxConnections` concurrent requests → 503 | yes | same | `requestsOverMaxConnectionsAre503` |
| Request body over 16 MiB → 413 | yes | same limit, HTTP/1 and HTTP/2 | `declaredBodyOverTheLimitIs413OnHttp1`, `chunkedBodyOverTheLimitIs413OnHttp1`, `bodyOverTheLimitIs413OnH2` |
| gzip (`maybeCompress`: ≥ 256 B, compressible types, Accept-Encoding with q > 0, not streamed, not 206, no Content-Encoding, no `no-transform`, only if smaller, Vary merged) | yes | same policy, zlib from `platform.zlib` | `gzipsACompressibleBodyForAClientThatAcceptsIt`, `gzipPolicyLeavesTheResponseAloneWhenItShould`, `gzipMergesVaryAndDropsContentLength`, `gzipOverHttp1AndH2FollowsAcceptEncoding`, `compressionDisabledByConfigSendsIdentity` |
| Streaming backpressure | bounded channel | bounded queue, pulled by the connection as it writes | `slowReaderHoldsTheStreamingHandlerBack` |
| Client gone ends the handler's writes | `write` returns false | producer coroutine cancelled; cleanup in `finally` | `sseClientThatDisconnectsEndsTheHandlersWrites` |
| Set-Cookie per cookie on the live response | yes | yes, buffered and streamed, HTTP/1 and HTTP/2 | `cookiesOnTheLiveResponseAreSeparateSetCookieFields` |
| Graceful stop: stop admitting, drain up to min(timeout, 5 s), then close | yes | yes; also asks connections to finish (HTTP/1 no keep-alive, HTTP/2 GOAWAY) | `stopLetsInFlightRequestsFinishAndRefusesNewConnections`, `stopCutsOffRequestsThatOutliveTheGracePeriod` |
| Multi-core | Tokio multi-thread runtime | one neton-io reactor per core | started log line `reactors=N` |
| Translation (headers, query, bytes) | conformance suite (macOS only) | conformance suite on macOS and Linux | `NetonStreamConformanceTest` (6 checks) |

### Differences in detail

- **Admission precedes upload**: a request must acquire a handler slot before its body is read.
  Nonempty/unknown bodies reserve twice their buffering capacity (buffer plus resize/copy headroom)
  from `maxBufferedRequestBytes`, until the handler exits. Reservations start at 2 KiB and grow
  before allocating larger arrays; empty GETs consume no body reservation and arm no upload timer.
  This bounds this adapter's active request-buffer reservations, not socket buffers, GC garbage,
  application-retained arrays or response buffers. The chunk queue is bounded by count, not bytes.
  Rejection is 503 before reading; HTTP/1 with unread body closes rather than draining it indefinitely.
  A body not received within `requestBodyTimeoutMillis` is answered with 408 and the connection closes;
  both reservations are released (hyper4k has no such limit, so slow large uploads need a larger value).
  On HTTP/1 the connection times it (http `Http1ServerConfig.bodyReadTimeoutMillis`) from the first time it
  has to wait for body bytes, so a body that arrived with its head costs no timer; on HTTP/2 the adapter
  times the whole read. A bodyless 503 retains keep-alive.
- **Cancellation**: disconnect/reset cancels the associated handler; HEAD and bodyless status
  responses abandon their streaming producers without waiting for the keep-alive connection to end.
  Handlers must use `finally` for cleanup, not rely on a subsequent successful `writeChunk`.
  Cancellation is cooperative; blocking or non-cancellable application code cannot be forcibly stopped.
- **Lifecycle**: start/stop transitions are serialized; a cancelled startup requests and awaits worker
  shutdown. Cancelling a stop caller cannot skip engine/TLS cleanup; concurrent stops await that cleanup.
- **413 on HTTP/1** comes from the http library before or while the body is read: status 413 with no
  body, then the connection closes. hyper4k sends `413` with the text `hyper4k: request body too
  large`. On HTTP/2 this adapter answers `413` with `request body too large` (text/plain).
- **Header timeouts**: the library's stricter defaults apply — 10 s to receive a request head, 60 s
  keep-alive idle (hyper: 30 s for both). Request line ≤ 8 KiB, header section ≤ 64 KiB, and
  Transfer-Encoding with Content-Length is rejected (400) — the library's security baseline.
- **Stopping**: the listener closes as soon as `stop()` begins (hyper4k keeps accepting and answers
  503 during the drain). Requests arriving on existing connections during the drain get 503, as in
  hyper4k.
- **Header names** arrive lowercase (as with hyper over the wire). The framework's header lookups
  are case-insensitive.
- Each streamed chunk is copied once (the caller may reuse its array after `writeChunk` returns).
- **Peer address**: taken from the socket with neton-io's `IoStream.peerAddress` right after
  accept, before TLS wraps it, as the IP alone (`::ffff:a.b.c.d` from a dual-stack listener becomes
  `a.b.c.d`; IPv6 in RFC 5952 form). `HttpRequest.peerAddress` is always the socket peer;
  `remoteAddress` prefers `X-Forwarded-For` as with every engine.

## Known gaps

- **WebSocket**: the library exists (`com.netonstream:websocket`) but the framework has no WebSocket
  API; nothing is wired here.
- **Request streaming**: the framework's dispatcher takes a complete `ByteArray` body, so request
  bodies are buffered (up to 16 MiB), as with hyper4k.
- **Trailers**: not declared; the framework has no API for them.
- **HTTP client adapter**: phase 2 (hyper4k also provides the framework's HTTP client).
- **Performance**: no comparison with hyper4k yet (phase 3). The library's own figures are in its
  SPEC §11.
- **Windows**: mingwX64 compiles; its tests have not been run (no Windows host in this round).
- **Distribution**: released dependencies use Maven Central. This adapter is in the BOM,
  but the `neton` aggregate still selects Hyper4k; select this adapter explicitly.

## Build wiring

Dependencies resolve from Maven Central. `-Pnetonstream.repository=<dir>` explicitly redirects
`io`, `http`, `tls` and their target artifacts to a staged Maven directory for release verification.
There is no implicit local Maven fallback or sibling source checkout.

## Package rule: `neton.http`

The framework and com.netonstream:http both use the package `neton.http` (owner decision). That is
safe only while no top-level declaration of the framework in `neton.http` has the same fully
qualified name as one of the library's: two classes with one name would clash for every application
that has both. So:

1. Inside this module, library types are imported under engine-specific aliases
   (`import neton.http.Request as StreamRequest`, `Response as StreamResponse`, `Body as StreamBody`,
   …); framework types are used by their own names.
2. `./gradlew :neton-http-netonstream:checkNetonHttpPackageClash` (part of `check`) lists the
   top-level declarations in package `neton.http` of every framework module's sources and of the
   library's published sources jar, and fails on any shared fully qualified name. Its report is
   `build/reports/neton-http-package-clash.txt`. Do not add a framework declaration named like a
   library one (`Request`, `Response`, `Body`, `Method`, `StatusCode`, `HttpError`, `HttpException`,
   `Version`, …).

## Tests

```
./gradlew :neton-http-netonstream:macosArm64Test
./gradlew :neton-http-netonstream:linuxX64Test         # on Linux
./gradlew :neton-http-netonstream:checkNetonHttpPackageClash
```

On a Linux host, neton-core's small C bridge is compiled with `clang`; if none is installed, the
one Kotlin/Native downloads works:
`PATH=~/.konan/dependencies/llvm-21-x86_64-linux-essentials-116/bin:$PATH ./gradlew ...`.
The linked test binary can also be run directly, once per reactor driver:
`NETON_IO_DRIVER=iouring|epoll neton-http-netonstream/build/bin/linuxX64/debugTest/test.kexe`.

`nativeTest` holds the unit tests (capabilities, validation, gzip policy); `posixTest` the conformance
suite and the socket tests (raw HTTP/1.1, the library's own HTTP/1.1 and HTTP/2 clients, TLS with
certificates generated by OpenSSL at test time).

Design notes: [docs/design.md](docs/design.md).

Review regressions are in `LifecycleSafetyTest`: cancelled startup/stop, disconnect before headers
(HTTP/1 and HTTP/2), HEAD/204 with a streaming handler, admission before reading, buffer-budget
exhaustion, upload timeout and reservation release. These are correctness checks, not evidence of
throughput improvement. Central publication of the dependencies is still required before an Arena PR.
