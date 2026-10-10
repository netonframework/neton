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

## Boundaries

- `neton.ws.spi`: experimental, engine-neutral provider and connection contracts.
- `neton.ws.engine.default`: the built-in library bridge; third parties implement the SPI.
- `neton-core`: protocol-neutral upgraded transport only.
- `neton-http`: HTTP framework and built-in default adapter; no WebSocket dependency.

The protocol engine alone answers peer Ping and Close frames. Applications must not call the
low-level connection concurrently from arbitrary threads. Its owner keeps reading during the
closing handshake and always calls `abort()` in `finally`; managed sessions enforce this.
The module is optional and is not added to the `neton` umbrella. Its built-in protocol dependency
is present even when selecting a third-party provider, matching the default HTTP engine model.
