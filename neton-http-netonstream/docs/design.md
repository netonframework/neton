# netonstream engine: design note

Scope: the server adapter only (phase 1). The engine contract is neton-core's `HttpAdapter`
(`start` suspends until `stop`), `HttpCapability` and `HttpServerConfig`; application behaviour is
the shared `BufferedHttpDispatcher`. The reference for every externally visible rule is the hyper4k
adapter (`Hyper4kHttpAdapter`, hyper4k's `Hyper4kServer` and `lib.rs`).

## Threads

`start()` runs inside the application's `runBlocking`, next to the shutdown monitor that calls
`stop()`. neton-io's reactor 0 blocks the thread it runs on, so the engine starts a worker thread
(`neton-stream-reactor-0`) that runs `runReactor { listenGroup(...) }`; `listenGroup` starts
`reactors - 1` more reactor threads. `start()` itself only awaits `CompletableDeferred`s that the
reactor thread completes (bound, stopped), so the application's event loop keeps running.

Each accepted connection lives on one reactor for its whole life (share-nothing). Handlers are
launched `UNDISPATCHED` on `Dispatchers.Default`: a handler that never suspends runs to completion
on the reactor inside the connection's service call and the response is already there when the
launch returns (no thread switch); one that suspends continues on the default pool, and the
connection coroutine resumes on its reactor when the response is ready. That is hyper4k's model
(inline on the Tokio worker, then the coroutine dispatcher) and keeps a slow handler off the
reactor.

## Request path

```
IoStream (neton-io) ─► [tlsAccept → TlsStream, alpn] ─► AutoServerConfig.serveConnection(stream, alpn, service)
    ─► HttpService.call(Request<Incoming>)                         (on the reactor)
         read body (≤ 16 MiB, else 413)
         admission: accepting? slot free? (else 503)
         BufferedHttpRequest (lazy header map over the library's HeaderMap)
         launch handler ─► dispatcher.dispatch(req, NetonStreamLiveResponse)
         await EngineResponse ─► gzip policy ─► Response<Body>
```

- **Body limit**: HTTP/1 uses the library's `Http1ServerConfig.maxRequestBodySize` (declared length:
  413 before the service; chunked: the body read throws `UserBodyTooLarge`, which the adapter lets
  through so the library answers 413 and closes). HTTP/2 has no such option in the library; the
  adapter checks `content-length` and counts DATA while reading, and answers 413 itself.
- **Timeout**: `lazyTimeout` as in hyper4k — the timer is armed only if the handler suspends; it
  covers the whole handler including a stream. Before the head is out the answer is the
  dispatcher's 504 envelope; once streaming, the body simply ends.
- **Admission**: a `Semaphore(maxConnections)` of concurrent requests plus an in-flight counter,
  checked twice around the increment, exactly as `AsyncRequestDispatcher.submit`.

## Streaming

`NetonStreamLiveResponse.stream {}` builds a `LiveBody` — a bounded channel (`streamQueueCapacity`
chunks) implementing the library's `Body` — and hands the head plus that body to the connection
through the pending response. The connection pulls frames as it writes them: HTTP/1 writes chunked
(flushing whenever the next frame is not ready yet), HTTP/2 writes DATA under flow control. A slow
reader stops the pulls, the channel fills, `writeChunk` suspends: backpressure without buffering the
response.

Client gone: the body watches the job of the coroutine serving the exchange (HTTP/1: the
connection's exchange job, which the library cancels when it sees the client close; HTTP/2: the
stream's coroutine, cancelled on RST_STREAM or connection end). When that job completes before the
body ended, or the frame read is cancelled, the body is marked gone and the channel cancelled: a
producer blocked in `send` wakes up and every `writeChunk` returns without writing from then on
(hyper4k's `write` returning false). The handler's own cancellation (timeout, shutdown) still
propagates.

## Stop

1. `accepting = false` — new requests on open connections get 503.
2. The listener closes (`TcpServerGroup.close`), and every connection is asked to finish
   (`AutoConnection.gracefulShutdown` on its own reactor: HTTP/1 stops keep-alive and closes when
   idle, HTTP/2 sends GOAWAY and closes once its streams are done).
3. Wait until no request is in flight and no connection is open, at most min(timeout, 5 s)
   (5 s when `timeout = 0`), as hyper4k's `awaitDrained`.
4. `shutdown(0)` cancels what is left; the reactors exit; the handler scope is cancelled and joined
   within the same grace.

## Package names

The framework and the library share `neton.http`. The adapter imports library types under `Stream*`
aliases; the build fails if a framework top-level declaration in `neton.http` ever takes a library
name (`checkNetonHttpPackageClash`). See the README.

## Not in this phase

WebSocket (no framework API), streamed request bodies (the dispatcher takes a `ByteArray`),
trailers, the peer address (no neton-io API yet), the HTTP client adapter (phase 2), performance
comparison with hyper4k (phase 3).
