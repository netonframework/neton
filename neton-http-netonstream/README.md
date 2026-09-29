# neton-http-netonstream

An HTTP server engine for Neton built on our own Kotlin/Native libraries instead of Rust (hyper4k)
or Ktor:

| Library | Coordinates | Role |
|---|---|---|
| neton-io | `com.netonstream:io:0.2.0-SNAPSHOT` | reactors (epoll / io_uring / kqueue / IOCP), multi-reactor TCP server |
| http | `com.netonstream:http:0.1.0-SNAPSHOT` | HTTP/1.1 (hyper 1.11.1), HTTP/2 (h2 0.4.19), both on one port (hyper-util `server::conn::auto`) |
| tls | `com.netonstream:tls:0.1.0-SNAPSHOT` | TLS as an `IoStream` over OpenSSL 4.0.2 (`com.netonstream:openssl:4.0.2`), ALPN |

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
| Client gone ends the handler's writes | `write` returns false | `writeChunk` returns without writing; `clientGone` set | `sseClientThatDisconnectsEndsTheHandlersWrites` |
| Set-Cookie per cookie on the live response | yes | yes, buffered and streamed, HTTP/1 and HTTP/2 | `cookiesOnTheLiveResponseAreSeparateSetCookieFields` |
| Graceful stop: stop admitting, drain up to min(timeout, 5 s), then close | yes | yes; also asks connections to finish (HTTP/1 no keep-alive, HTTP/2 GOAWAY) | `stopLetsInFlightRequestsFinishAndRefusesNewConnections`, `stopCutsOffRequestsThatOutliveTheGracePeriod` |
| Multi-core | Tokio multi-thread runtime | one neton-io reactor per core | started log line `reactors=N` |
| Translation (headers, query, bytes) | conformance suite (macOS only) | conformance suite on macOS and Linux | `NetonStreamConformanceTest` (6 checks) |

### Differences in detail

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

## Known gaps

- **Peer address**: `com.netonstream:io` does not expose a TCP stream's peer address, so
  `HttpRequest.peerAddress` / `remoteAddress` are empty. Consequence: IP-keyed rate limits
  (`ClientIpResolver`) see every client as `unknown` and share one bucket, and access logs have no
  client IP unless `X-Forwarded-For` is present. Needs a peer-address API on neton-io's streams;
  until then do not use this engine where per-IP rate limiting matters.
- **WebSocket**: the library exists (`com.netonstream:websocket`) but the framework has no WebSocket
  API; nothing is wired here.
- **Request streaming**: the framework's dispatcher takes a complete `ByteArray` body, so request
  bodies are buffered (up to 16 MiB), as with hyper4k.
- **Trailers**: not declared; the framework has no API for them.
- **HTTP client adapter**: phase 2 (hyper4k also provides the framework's HTTP client).
- **Performance**: no comparison with hyper4k yet (phase 3). The library's own figures are in its
  SPEC §11.
- **Windows**: mingwX64 compiles; its tests have not been run (no Windows host in this round).
- **Distribution**: the netonstream libraries are SNAPSHOTs in mavenLocal; this module is not in the
  BOM or the `neton` aggregate until they are on Maven Central.

## Build wiring

`build.gradle.kts` adds mavenLocal inside an `exclusiveContent` block that matches only
`com.netonstream:io`, `http`, `tls` and their per-target artifacts: those resolve only from
mavenLocal, and everything else (including `com.netonstream:openssl` and `hyper4k`) keeps resolving
from Maven Central. No other module's repositories change. An application using this engine needs
the same block (see `examples/netonstream-hello/build.gradle.kts`).
`-Pnetonstream.repository=<dir>` points the filter at a staged Maven directory instead.

Publish the libraries first: `./gradlew publishToMavenLocal` in `neton-io`, `http` and `tls` (on
Linux, publish the `linuxX64` publications and copy the multiplatform root modules from a machine
that built all targets).

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
