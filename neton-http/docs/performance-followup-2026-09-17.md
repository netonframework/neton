# Performance follow-up: build and shutdown prerequisites

## Engine artifact alignment

The current adapter requires `Hyper4kRequest.peerAddress`, absent from published
Hyper4k 0.9.1. Engine Gradle/Cargo versions and the adapter dependency now target
0.9.2. This is a **pending release**, not a claim that Central contains 0.9.2.
Do not publish Neton against that version before the matching engine is released.

Two explicit local workflows are available:

```sh
# Fast source development; not an artifact compatibility check.
./gradlew :neton-http-hyper4k:macosArm64Test -Phyper4k.local=true

# Build a local Maven repository and test the packaged engine dependency.
# No uploads, no ~/.m2 changes, and no includeBuild substitution.
bash scripts/verify-hyper4k-artifact.sh
```

The second command currently supports macOS hosts. Producing Kotlin root metadata
may build cinterops for the other declared targets too; their Rust cross-toolchains
must be installed. Only the host artifact is linked and executed by this check.

`-Phyper4k.repository=/absolute/path` selects a local file repository exclusively
for Hyper4k modules. It cannot be combined with `-Phyper4k.local=true`; a missing
source checkout also fails explicitly instead of silently using a binary.
Without either option, dependency resolution still uses Central and requires
the 0.9.2 release to exist. This change does not hide missing publication.

## Shared-runtime shutdown defect

Before wiring shared runtimes into Kotlin, a socket test reproduced this failure:
`hyper4k_server_stop` returned while a keep-alive connection on that listener
remained open. Shared runtimes outlive one listener, so dropping the listener's
runtime reference does not cancel its detached connection tasks. Disposing Kotlin
`StableRef` at that point would not be safe.

The fix gives each listener a connection JoinSet and a task handle. Stop signals
the accept loop, cancels and joins its connections, then joins the accept task.
Hyper also spawns HTTP/2 stream futures outside the connection task; a
listener-scoped executor tracks those futures with cancellation and TaskTracker.
They are joined as well before stop returns. Other listeners keep their runtime.

Four real-socket tests cover:

- Stopped keep-alives close, siblings remain usable, and the port can be rebound.
- A failed bind does not shut down a working sibling.
- Stop waits for an executing HTTP/1 callback before user_data can be freed.
- Stop also waits for an executing HTTP/2 stream callback.

This is a correctness prerequisite, **not a proven throughput improvement**.
Connection/stream bookkeeping has a cost and must be included in subsequent
HTTP A/B measurements. Default Kotlin listener runtime ownership is unchanged.
Stop must be called outside a Tokio runtime and outside request callbacks;
application work should drain before calling it. This is cancellation plus a
callback-lifetime barrier, not a promise to deliver all in-flight responses.

## Still unverified

No Linux Arena A/B or 32/64-core measurement was performed in this follow-up.
No conclusion is made about the historical Arena regression or worker counts.
The method/header/diagnostic optimizations retain their separate evidence in
`performance-audit-2026-09-16.md`; their microbenchmarks are not HTTP throughput.
Default runtime sharing and synchronous responder registration changes are not
enabled without their own lifecycle and performance acceptance.

## Verification results

- `cargo test --locked --lib`: 136 passed, two experiments ignored.
- `cargo check --locked --lib`: passed without warnings.
- Hyper4k `macosArm64Test`: passed after relinking the updated engine.
- Local Maven packaging and Neton `macosArm64Test`: passed with source
  substitution disabled. `dependencyInsight` confirmed the published variants of
  `com.netonstream:hyper4k:0.9.2` and `hyper4k-macosarm64:0.9.2`, not project variants.
- Rust release builds for all five configured targets succeeded as part of KMP
  metadata/cinterop production. This is build coverage, not five-platform runtime
  validation.
- Supplying both source and artifact verification flags failed at settings
  evaluation with the expected explicit message.

An additional global dependency refresh hit a Maven Central TLS transport failure
while resolving kotlin-test. The preceding full artifact run had passed; an offline
recheck with cached dependencies passed too. No TLS settings or dependency versions
were weakened to bypass the transport failure. No public publication was performed.
