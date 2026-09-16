# HTTP performance audit, 2026-09-16

## Scope and evidence

Reviewed local Neton beta13/beta15 tags, current Neton `062d003`, Hyper4k
`033c21e`, and the HttpArena Neton entry. No remote machines were provisioned,
no benchmark comments were posted, and no artifacts were published.

Historical Arena scores are observations, not controlled A/B measurements.
`RPS / CPU%` is useful context but is not environment-independent. CPU frequency,
SMT, cache locality, contention, GC phase and measurement windows affect it.
The checked-out Arena `scripts/lib/stats.sh` averages Docker CPU samples;
`scripts/benchmark.sh` also captures cgroup CPU deltas. Prefer CPU delta divided
by successful requests over the same load interval, alongside RPS, errors and
latencies. Do not compare a throughput winner's CPU to a different run.

## Findings

1. beta13 to beta15 changes Hyper4k 0.7.0 to 0.8.0 as well as Neton code.
   Compression wiring and the JSON serializer change are not the entire artifact
   difference. The engine lockfile adds compression dependencies. The SlotPool
   prototype was not wired into this request path.
2. Current Hyper4k increments `TOTAL_REQS` on every registration and `SYNC_HITS`
   on synchronous delivery even without `HYPER4K_STATS`. Only the reporter was
   conditional. These are additional shared atomic writes, not proof of the
   historical regression. The patch gates counting as well as output, with `1`
   explicitly enabling diagnostics. Responder IDs and ownership are unchanged.
3. A common complete text response copies its headers through a general-purpose
   mutable map and mutable value lists. The patch uses empty/singleton snapshots
   when possible and copies values for larger snapshots. CORS merging retains
   the prior path. No borrowed memory, pooled contexts or response-body caching
   is introduced. Header mutation after snapshotting cannot change the snapshot.
4. The current Neton adapter references `Hyper4kRequest.peerAddress`, but its
   declared published dependency, Hyper4k 0.9.1, lacks that member. Default
   compilation fails. Local source substitution compiles, but is not validation
   of a published consumer. Resolve the dependency version before releasing.
5. Shared-runtime Rust entry points exist, but Kotlin `Hyper4kServer.start()`
   still calls `hyper4k_server_start` / `hyper4k_server_start_tls`, not
   `hyper4k_server_start_on`. The actual application path has not adopted the
   shared runtime. This is an integration opportunity, not evidence that idle
   listeners caused the historical regression. Ownership and independent listener
   shutdown require their own tests before changing this path.

## Local snapshot experiment

Run from the Neton repository:

```sh
NETON_HEADER_BENCH=1 ./gradlew :neton-http-hyper4k:macosArm64Test \
  -Phyper4k.local=true --console=plain \
  -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g'
```

If the test task is up-to-date, rerun its generated test executable with
`NETON_HEADER_BENCH=1`, or force the test task to execute; environment changes
alone are not Gradle task inputs. The XML stdout must contain all twelve rows.

This is an opt-in microbenchmark in a macOS ARM64 **debug test binary**, not a
release-mode HTTP benchmark. It isolates snapshot construction from one existing
response with one Content-Type header. Both algorithms produce a response wrapper
and consume its header/body; the control retains the old production algorithm.
It does not measure request parsing, networking, GC under HTTP load, or CORS.

After one warmup per variant, 200,000 iterations per row, alternating order:

| Round | Old ns/op | New ns/op |
| --- | ---: | ---: |
| 0 | 3853 | 2472 |
| 1 | 3819 | 2534 |
| 2 | 3994 | 2706 |
| 3 | 3746 | 2600 |
| 4 | 4328 | 2671 |
| 5 | 3892 | 2671 |

Median: 3872.5 versus 2635.5 ns/op, about 32% less time **in this isolated
operation**. No Arena throughput gain is established by this measurement.

## End-to-end acceptance still required

Local verification: Hyper4k `cargo test --locked --lib` passed 129 tests.
Neton's `:neton-http-hyper4k:macosArm64Test -Phyper4k.local=true` passed after
relinking against the modified Rust library (34 correctness tests plus the
opt-in benchmark test). The benchmark was also explicitly enabled in a separate
run to produce the table above. The default published-dependency build remains
blocked by the pre-existing peerAddress version mismatch described above.

Keep the actual Arena application, dataset, toolchain, engine, logging, GC,
listeners and DB linkage identical. Build four separately identified artifacts:

- A: unchanged current sources.
- B: A plus the diagnostics gate only.
- C: A plus the header snapshot change only.
- D: both changes, only after individual measurements.

Run interleaved A/B/B/A and A/C/C/A rounds with the same pinned gcannon build
and Arena arguments. Record executable hashes, server/load-generator placement,
warmup and measurement duration, successful requests, errors, CPU deltas, RSS,
and latency. Verify response bytes and repeat at multiple CPU/concurrency levels.
Do not accept a throughput increase obtained by errors or changed response semantics.

The beta13/beta15 historical regression investigation is separate: hold the
Arena entry and build environment fixed while crossing framework and engine
versions (where ABI-compatible). Do not mix DB linkage or new profiles into that
comparison. Neither this audit nor the local microbenchmark establishes its cause.

## Follow-up: FFI method allocation

`Hyper4kServer.submit` previously decoded every method via a temporary ByteArray
and a new String. `copyHttpMethod` now compares the borrowed slice against the
nine standard method tokens and returns immutable string constants. The borrowed
pointer never escapes the callback. Unknown methods still copy and decode, with
case preserved. Tests check constant identity, non-NUL-terminated slices,
near-matches, and ownership after the original buffer is overwritten.

This removes two allocations for a standard method, not all request allocations.
It does not pool contexts, change coroutine scheduling, or increase the GC heap.

Local opt-in debug microbenchmark (macOS ARM64, POST, 500,000 operations per row,
warmup for both variants, alternating order, no concurrent build/test run):

| Round | Copy/decode ns/op | Constant lookup ns/op |
| --- | ---: | ---: |
| 0 | 1425 | 957 |
| 1 | 1511 | 987 |
| 2 | 1368 | 1181 |
| 3 | 1296 | 1067 |
| 4 | 1705 | 1002 |
| 5 | 1338 | 906 |

Median 1396.5 versus 994.5 ns/op: about 29% less time **for this operation**.
This is not an end-to-end HTTP result. Reproduce after building Hyper4k tests:

```sh
HYPER4K_METHOD_BENCH=1 build/bin/macosArm64/debugTest/test.kexe \
  '--ktest_filter=hyper4k.RequestMethodLookupTest.*'
```

Add an isolated method-only variant to the A/B artifact matrix above before
attributing any HTTP throughput gain to this change.

## Rejected shortcut: experimental HTTP/1 pipeline flush

Arena's `frameworks/hyper/src/main.rs` enables `pipeline_flush(true)`, whereas
Hyper4k uses the default false. A new test-only socket harness compares these
settings through Hyper4k's real `handle` and C response callback path. It checks
mixed GET/Content-Length POST/chunked POST response order, a pending handler,
and an incomplete following request.

On the current pinned Hyper version, true **holds the first response** when a
complete request is followed in the same write by an incomplete second request
header. The first response timed out after five seconds; false returned it
normally. Completing the second header releases both responses. A separate
opt-in reproducer verifies this behavior. Thus the throughput-oriented setting
was NOT enabled in production. No public configuration or TLS behavior changed.

From `hyper4k/lib`, reproduce the safety result:

```sh
cargo test --locked --lib pipeline_tests -- --nocapture
cargo test --locked --lib experimental_flush_stalls -- --ignored --nocapture
```

An ignored `compare_flush_policies` TCP experiment is also retained. It is an
engine-only test harness, not Neton, TLS, Arena, or a release acceptance result.
Only run it alone in release mode when evaluating alternatives. A faster result
does not override the partial-request behavior above.

## Test reliability finding

Two full Rust runs failed the existing client truncation test's expectation of
exactly one headers callback (actual zero); the same test passed alone. Its peer
disconnected immediately after writing headers, racing bridge delivery. The
frozen ABI explicitly permits discarding committed-but-undelivered headers on
truncation. The fixture now waits for a headers callback before disconnecting,
so it actually exercises the intended already-delivered-response case. The
original assertions (one headers callback, one connection, TRUNCATED) remain;
no client production semantics or retries were changed.

Final follow-up verification: Rust library suite 132 passed, two opt-in
experiments ignored by default (both explicitly exercised separately). Hyper4k
Kotlin suite 42 correctness tests passed, plus an opt-in benchmark entry;
Neton Hyper4k adapter suite 34 correctness tests passed, plus its opt-in benchmark
entry, using `-Phyper4k.local=true`. Both microbenchmarks were explicitly run to
obtain their measurements rather than treating their disabled entries as evidence.
No Arena benchmark, release, commit, push, or remote-machine deployment was made.
