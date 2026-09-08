# Request object pooling — audit and design

Goal (from the user): don't allocate a fresh HttpContext/Request/Response per
request. Pre-build a bounded set of request-object skeletons; a request leases
one, fills it, and returns it after every consumer is done. This is the
fasthttp/Netty model, not caching request data.

Not the goal: bypass Kotlin/Native GC (pooled objects stay on the GC heap; the
win is fewer allocations and less garbage, not escaping GC), and not a single
shared mutable map for per-request data (that races under concurrency).

## Per-request allocation audit (baseline GET, WARN, after the method + query-scan fixes)

FFI boundary (hyper4k `submit`), per request:
- method / path / query: each `copyToByteArray().decodeToString()` → a transient
  ByteArray + a String (≈6 allocations)
- rawHeaderBytes, body: one ByteArray each (2)
- `Hyper4kRequest` object (1)
- coroutine: `scope.launch(UNDISPATCHED)` continuation/Job state (several)

neton dispatch, per request:
- `BufferedHttpRequest` (+2 function refs for singleHeader/headersProvider)
- `BufferedHttpContext`, `BufferedHttpRequestView`
- `ArgsView` (+2 lambdas for query scan)
- `DispatchOutcome` (data class)
- `BufferedHttpResponse` (+ response body bytes)
- `toHyper4k` → `Hyper4kResponse` (+ encoded header bytes)

≈25–30 allocations/request. Split:
- **Poolable skeletons** (structure stable, fields refillable): Hyper4kRequest,
  BufferedHttpRequest, BufferedHttpContext, BufferedHttpRequestView, ArgsView,
  BufferedHttpResponse, Hyper4kResponse.
- **Per-request data** (content differs each request): the FFI-copied strings and
  the header/body/response byte arrays. Content cannot be pooled; the *buffers*
  can be, separately, with a return-after-write contract.
- **Coroutine state**: not pooled (do not reuse a completed Job/continuation).

## Design: a bounded `RequestSlot` pool

```
RequestSlot (one per in-flight request)
  ├─ Hyper4kRequest fields (mutable: method/path/query/headers/body set per request)
  ├─ BufferedHttpContext + Request view + Response state
  ├─ reusable param/header containers
  └─ optional borrowed buffer (from a separate sized buffer pool)

lease → init(fields) → dispatch → await ALL consumers done → reset → return
```

### Non-negotiable safety rules (a leak here corrupts a different user's request)

1. **One slot per request; never shared while live.** Return must be safe from
   whatever thread the coroutine resumed on, without a single global lock becoming
   the bottleneck (shard the pool, or a lock-free stack).
2. **"Done" is not "handler returned."** Streaming responses, async writes and
   managed sub-tasks must all have finished before return; cancellation and
   exceptions go through the same single release path (return exactly once).
3. **reset clears every request reference:** identity, exception, body, response
   headers, attributes, callbacks. An exception-inflated large buffer is dropped,
   not kept in the pool forever.
4. **Application code must not retain the context past the request.** Background
   work copies what it needs. A version/generation counter on the slot can catch
   some use-after-return in debug, but is not a correctness guarantee.

### Rollout

- Behind a flag; the plain per-request-allocation path stays as the A/B control.
- Bounded: separate knobs for pre-warmed slots, max in-flight, max retained bytes.
  Do NOT hardcode 65535 as a default — idle servers must not hold max memory, and
  connections != request slots (one h2 connection multiplexes several).
- Acceptance is NOT just RPS: allocation bytes/request, CPU/request, p99, peak
  RSS, and behaviour under overload + recovery. Must also pass cancel, streaming,
  slow-consumer, disconnect and cross-thread-resume tests with no cross-request
  data bleed.

## Why not a thread-local single request object

A request can suspend and resume on a different thread, so a thread-local
"one live request object" would be handed to the wrong request. Slots must be
leased/returned explicitly, not bound to a thread.

## Status

Audit done. Design specified. Implementation is a substantial, high-risk change
to the core request lifecycle and will be built incrementally behind the flag,
each increment with the tests above, keeping the plain path as control — not
rushed. First increment candidate: pool the response-side byte buffer with the
return-after-write contract (smallest lifecycle surface), measured for
allocation-bytes/request before touching the context skeleton.
