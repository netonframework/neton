# Local allocation optimization (2026-10-01)

## Main integration (2026-10-15)

The optimization is integrated into the default engine in `neton-http`, under
`neton.http.engine.default`. The paths and commands below describe the original
experiment, before the module consolidation. For the current checkout, run
`./gradlew :neton-http:macosArm64Test :neton-http:checkNetonHttpPackageClash`.
When the required OpenSSL artifact is not published, add
`-Pnetonstream.local.openssl=true` to use the sibling source checkout. This validates
the host target only, not dependency availability for the full platform matrix.
The historical measurements below are not new measurements of the merged main branch.

## Scope and control

Control: Neton commit `2d48198` (already includes connection-local handler jobs and combined
admission accounting). This is NOT a beta22-vs-beta23 comparison. No library versions, admission
limits, timeouts, GC settings, reactor counts or cancellation paths change in this experiment.
Hyper4k is not modified. Nothing is published by this experiment.

Two independently buildable changes:

1. `NetonStreamLiveResponse.kt`: keep zero headers or one single-valued header in fields. Promote
   to the existing linked-map/mutable-list representation for another header or a repeated value.
   Snapshots remain independent of subsequent mutations. This is per-response storage, not a pool
   or a cache of response data, and adds no shared mutable state.
2. `NetonStreamHttpAdapter.kt`, `readBody`: use the shared empty array until data arrives when
   length is unknown. Allocate the first nonempty chunk's exact size, then grow geometrically if
   more data arrives. Continue reading trailers and end-of-stream; never turn a late read error
   into a successful partial response. Capacity is reserved before allocation, as before.

The second change has a tradeoff: a fragmented body with a small first frame may allocate/copy
that small frame once more than the old initial 1 KiB strategy. Later growth remains geometric.
It favors complete small bodies, not every upload pattern. The initial admission reservation is
unchanged; smaller actual arrays do not relax the global byte budget.

## Deterministic checks

- The existing 56 adapter tests pass before editing.
- Five new response-header tests pass against both the old and new implementation. They cover
  case-insensitive lookup, insertion order, repeated cookies, CORS, Content-Length filtering,
  snapshot reuse/isolation, and 1,000 mutations compared to the original map model.
- Eight request-body tests cover allocation capacities, known/unknown lengths, empty frames,
  trailers, fragmentation, limits, reservation failure, late errors and cancellation.
- All 69 adapter tests pass on macOS ARM64. Linux x64/ARM64 and Windows x64 main-source
  compilation and the package-clash check pass. These are not Linux/Windows runtime tests.

Commands from the Neton checkout:

```sh
./gradlew :neton-http-netonstream:macosArm64Test
./gradlew :neton-http-netonstream:compileKotlinLinuxX64 :neton-http-netonstream:compileKotlinLinuxArm64 :neton-http-netonstream:compileKotlinMingwX64
./gradlew :neton-http-netonstream:checkNetonHttpPackageClash
```

## Measurements

Local working artifacts: `/tmp/neton-local-opt.qDmuQA/`. The unchanged Arena release entry is
`/Users/zoujiaqing/projects/PulseKit/bench-arena/entries/release/neton`. Gradle's explicit
`--include-build` substitutes the framework sources; protocol libraries remain published artifacts.
The three normal release binaries are control, headers-only, and headers-plus-body. Their hashes
and benchmark configuration are retained with the results. A separate diagnostic entry adds only
`-Xallocator=std`; it must not be used for throughput claims.

Allocation measurement uses `bench-arena/measure.py` and `alloccount.c`, 16 keep-alive connections,
30,000 responses (10,000 each GET, Content-Length POST and chunked POST), checked byte-for-byte.
The method counts successful intercepted malloc-family calls and requested bytes, with an idle
rate subtraction. These are diagnostic-build process-wide allocation calls, not production Kotlin
object counts, live memory, RSS, or GC pause time. Raw windows include binary/tool SHA-256 hashes.

| Diagnostic build | Calls/request, run 1 / run 2 | Requested bytes/request, run 1 / run 2 |
| --- | --- | --- |
| Control `2d48198` | 104.93 / 104.93 | 6,565 / 6,565 |
| Both changes | 89.27 / 89.27 | 5,338 / 5,338 |

For this workload the diagnostic build makes 14.9% fewer intercepted allocation calls and requests
18.7% fewer bytes. The two samples of each binary each check 30,000 responses. The raw windows are
in `measurements/2026-10-01/{before,after}-{1,2}.window.json`. Do not compare these numbers directly
to older beta21 measurements: this control already has the HTTP and handler-job optimizations.

To reproduce the diagnostic runs, build the same Arena entry with `-Xallocator=std`, first with
`--include-build` pointing to an archived `2d48198` source tree, then to this checkout. After each
build run:

```sh
python3 /Users/zoujiaqing/projects/PulseKit/bench-arena/measure.py --entry ENTRY --label LABEL --per-shape 10000 --conns 16 --out OUTPUT
```

The normal release throughput A/B did not start: the harness rejected the host at its preflight
noise gate (other processes 200-286% CPU vs the configured 100% ceiling). The failed attempt and
three binary hashes are in `measurements/2026-10-01/throughput-attempt.log`; its configuration is
`bench-config.json`. No RPS or p99 improvement is claimed, and no unrelated processes were stopped
to make the test pass. This check does not explain or excuse the earlier Arena regression.

Separately, the normal release control, headers-only and combined binaries each pass 30,000
byte-for-byte response checks over 16 keep-alive connections (10,000 of each baseline shape),
plus pre/post checks. See `measurements/2026-10-01/release-correctness.jsonl`. These checks validate
the optimized release path but are not a throughput benchmark.

## Acceptance boundary

Less allocation does not prove higher RPS, better p99, or the cause of the Arena regression.
Do not publish an Arena recovery claim from this Mac or replace correctness checks with a score.
Before release, compare these changes independently under the same Linux driver, gcannon version,
three-request mix, 4,096 connections, listeners, CPU limits and reactor count. Retain the corrected
accept path in every comparison; record errors, per-connection coverage and per-reactor work.
