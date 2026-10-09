# neton-websocket

Optional framework module with a built-in `DefaultWebSocketEngineProvider` wrapping
`com.netonstream:websocket`. No separate `neton-websocket-netonstream` artifact is needed.

```kotlin
import neton.http.http
import neton.ws.websocket

Neton.run(args) {
    http { port = 8080 }
    websocket { } // installs the built-in provider
    // websocket(::MyProvider) { } // explicit replacement, not an additional installation
}
```

## Current implementation boundary

This is the engine integration foundation, NOT a completed WebSocket server feature.
Provider installation, compatibility validation, protocol handshake and executor-confined
engine connections are implemented. The built-in provider does not yet implement shared
preallocation budgets and deliberately does not advertise `PREALLOCATION_BUDGET`.
Requiring that capability fails validation instead of silently dropping the limit.

HTTP route upgrade dispatch, the business `WebSocketSession`, annotation/KSP generation,
managed heartbeat, shared budgets and graceful server shutdown are NOT implemented here.
`websocket { }` installs a provider only; it does not register or serve an endpoint. The HTTP
adapter does not declare protocol-upgrade capability until the route handoff is implemented.
Do not publish this module as a completed implementation of the WebSocket SPEC.

## Boundaries

- `neton.ws.spi`: experimental, engine-neutral provider and connection contracts.
- `neton.ws.engine.default`: the built-in library bridge; third parties implement the SPI.
- `neton-core`: protocol-neutral upgraded transport only.
- `neton-http`: HTTP framework and built-in default adapter; no WebSocket dependency.

The protocol engine alone answers peer Ping and Close frames. Applications must not call the
low-level connection concurrently from arbitrary threads. Its owner keeps reading during the
closing handshake and always calls `abort()` in `finally`; managed sessions will enforce this.
The module is optional and is not added to the `neton` umbrella. Its built-in protocol dependency
is present even when selecting a third-party provider, matching the default HTTP engine model.
