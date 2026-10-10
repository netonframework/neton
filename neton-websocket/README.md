# neton-websocket

Optional framework module with a built-in `DefaultWebSocketEngineProvider` wrapping
`com.netonstream:websocket`. No separate `neton-websocket-netonstream` artifact is needed.

```kotlin
import neton.http.http
import neton.ws.websocket
import neton.ws.webSocket
import neton.routing.routing
import kotlinx.coroutines.flow.collect

Neton.run(args) {
    http { port = 8080 }
    websocket { } // installs the built-in provider
    // websocket(::MyProvider) { } // explicit replacement, not an additional installation
    routing {
        webSocket("/echo") { session ->
            session.incoming.collect { session.send(it) }
        }
    }
}
```

## Current implementation boundary

HTTP/1.1 upgrade, DSL routes, `@WebSocket` controller generation, managed sessions,
heartbeat, bounded queues and shutdown are implemented. Handshakes pass through the HTTP
security/rate-limit path; parameter binding and Origin checks happen before 101. The HTTP
request permit is released at handoff, while TCP and WebSocket connection limits remain.
See `examples/websocket-echo` for both entry points. The default HTTP adapter supports upgrade;
other adapters must explicitly implement `PROTOCOL_UPGRADE` and a compatible provider.

Control and protocol operations stay on the connection executor; business handlers run on
`Dispatchers.Default`. Only one collector may consume `incoming`. `send` means accepted, not
delivered. A normal local close drains accepted messages before writing Close; shutdown, peer
close or failure may discard queued messages. A timed-out drain is reported as abnormal, not 1000.
Binary arrays are borrowed until send returns and copied after budget reservation;
do not mutate them during send. Quiet connections that answer Pong remain connected. Ping is
sent every 30 seconds by default, with a 10-second Pong deadline starting after write completion.
Consumer stalls and blocked writes have separate deadlines. Business-message idle timeout is off.

The default queue budget is 512 MiB globally and 8 MiB per connection per direction. Admission
counts payload bytes once, including in-progress inbound fragments. It is NOT an RSS ceiling:
copies, string representation, spare buffer capacity, codec/TLS buffers, object overhead and
business-owned messages are separate. Waiting sends also have count and retained-byte
limits. Resource exhaustion closes the growing inbound connection with 1013; control/close
frames do not wait for application queue budget. Shutdown rejects new tickets and closes active
or pending upgrades with 1001. Uncooperative business code cannot block transport cleanup forever.

Budget waiting has no network-write timeout: it ends on capacity, caller cancellation or closure.
Closure throws `WebSocketClosedException`, not a coroutine cancellation exception. Waiting-send
counters are only acquired after immediate enqueue fails. Actual network writes remain timed.
Consumer stalls use 1011; the healthy inbound enqueue path does not allocate a timeout.
Global budget wakeup contention and handler-executor performance still need measured follow-up.

Missing Origin is allowed for non-browser clients; otherwise the default is same-origin.
Use explicit `allowedOrigins` for cross-origin browser access. Compression, HTTP/2/3 extended
CONNECT, Hub and typed-message APIs are outside this release. `compression=true` is rejected.

Validation includes macOS runtime/protocol tests, real TCP and verified TLS upgrade/echo, pre-read frames,
HTTP quota release, early Pong, budget rejection and shutdown; Linux x64/arm64 compile checks.
Linux runtime, Windows and controlled performance acceptance remain
release gates. No throughput or production-readiness claim follows from unit-test counts.
Development currently requires the sibling WebSocket library source admission changes.

## Session contract migration

Sessions expose `handshake: HandshakeInfo`, not the mutable HTTP context or its response.
Identity implements the existing `Identity` interface but is a detached snapshot, not a
live authorization check. Headers are omitted unless explicitly allowed by
`handshakeHeaderNames`; retained UTF-8 snapshot content is limited by
`maxHandshakeSnapshotBytes` (default 64 KiB). This payload limit is not an RSS limit.

Use `awaitClosed()` instead of a publicly cancellable deferred. Cancelling one observer
does not cancel other observers or the transport. `CloseResult` distinguishes the original
trigger, requested/sent/received Close frames, and final termination. An empty peer Close
has a null code, not 1000. Closure is published after transport/queue cleanup and before
waiting for business cleanup, so business `finally` may await it without a cyclic wait.

`incoming` permits one collector at a time and can be collected again after `first()` or
`take()` finishes; unread messages continue without replay. Handler child coroutines must
finish before normal handler completion closes the connection. Shared handler fields are
not connection-local. Register an injectable `WebSocketHandler` directly with
`webSocket("/chat", handler)` or keep using a trailing lambda.

Each connection has a unique `connectionId`, unrelated to its authenticated user ID or login
session/token ID. Reconnecting creates a new connection ID; the existing login session can
remain valid. Multiple connections sharing that login session have distinct connection IDs
and may have different business subscriptions. The connection ID is not a credential.
Application user/session registries, authorization-domain separation and subscriptions remain
application concerns. They are not automatically installed by the WebSocket component.

## Inbound policies and engine verification

`webSocket("/push", inboundPolicy = InboundPolicy.REJECT_DATA) { ... }` rejects data with
1008 before allocating its payload. `DISCARD_DATA` validates framing, masking, message size
and incremental UTF-8 without assembling messages. Controls still work. The default is
`BACKPRESSURE`. Controller annotations and Handler-object overloads accept the same policy.
Unsupported policies fail route validation before the server starts. Non-delivery policies
currently require uncompressed connections; the framework does not negotiate compression.

The protocol driver yields after at most 32 discard processing turns, each consuming at most
16 KiB. Empty frames also consume a turn. This bounds individual work batches, not tail
latency under arbitrary load. Large-scale mixed-load performance acceptance remains pending.

Third-party providers can run `neton.ws.conformance.WebSocketEngineConformanceSuite` with
an independent peer fixture. Its checks cover text/empty Close, automatic Pong, preallocation
refusal, frame limits, rejection, validated discard and abort waking a read. Declared capabilities
must not skip their checks. `RuntimeTest` separately exercises common session policy against
controllable fake engines. These tests supplement, not replace, full protocol interoperability
and platform validation. API/SPI stability is not declared yet.

## Module boundaries

- `neton.ws.spi`: experimental, engine-neutral provider and connection contracts.
- `neton.ws.engine.default`: the built-in library bridge; third parties implement the SPI.
- `neton-core`: protocol-neutral upgraded transport only.
- `neton-http`: HTTP framework and built-in default adapter; no WebSocket dependency.

The protocol engine alone answers peer Ping and Close frames. Applications must not call the
low-level connection concurrently from arbitrary threads. Its owner keeps reading during the
closing handshake and always calls `abort()` in `finally`; managed sessions enforce this.
The module is optional and is not added to the `neton` umbrella. Its built-in protocol dependency
is present even when selecting a third-party provider, matching the default HTTP engine model.
