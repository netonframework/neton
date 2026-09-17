# Post-beta18 performance experiment

Status: experimental, not a release or a demonstrated HttpArena gain.

## Objective

Prioritize sustained throughput and tail latency. Additional bounded memory is
acceptable if it buys measured performance; minimum RSS is not the objective.
Correctness, bounded resource ownership and overload behavior remain mandatory.

## Implemented candidate

Hyper4k can reuse a thread-private byte buffer when decoding request path, query
and peer-address slices into owned Kotlin strings. Enable before process startup:

```
HYPER4K_REQUEST_STRING_SCRATCH=1
```

The default is off. Retention is capped at 4096 bytes per participating thread;
larger fields use the original allocation path. This removes the temporary byte
array on eligible calls after warmup, not the returned String allocation or the
native-to-managed copy. There is no pooled request context, borrowed String,
cross-thread scratch sharing, or suspension inside decoding.

Tests cover exact-length, malformed UTF-8, embedded NUL, source invalidation,
buffer reuse, short-after-long inputs, retention boundaries and coroutine
resumption. Public NUL-terminated pointer conversion is deliberately not used
for ABI slices, which need not have a terminator.

Validation: Hyper4k's 48 native tests pass with the switch off and on; the Neton
Hyper4k adapter's 35 tests pass against the local candidate with the switch on.
Release example linking and the harness syntax check also pass. This is macOS
ARM64 verification; no new Linux or Arena performance result is claimed.

## Measurement outcome

The optimized release microbenchmark showed lower times for 512-byte input,
but short-field results were mixed. This is not evidence of full-HTTP throughput.

An eight-run alternating HTTP comparison was completed on a macOS ARM64 desktop,
using 4 Tokio workers, 128 connections, 2 wrk threads, a 1024 MiB GC minimum and
2048 MiB target. Both binaries used the same local Neton example, not the exact
HttpArena entry. A used published Hyper4k 0.9.2; B used the local candidate with
scratch always enabled before the opt-in switch was added.

| Order | Variant | RPS | p99 |
| --- | --- | ---: | ---: |
| 1 | A | 139161 | 2.54 ms |
| 2 | B | 127428 | 5.08 ms |
| 3 | B | 98572 | 15.23 ms |
| 4 | A | 125526 | 6.11 ms |
| 5 | A | 122602 | 4.82 ms |
| 6 | B | 44357 | 47.90 ms |
| 7 | B | 29611 | 100.42 ms |
| 8 | A | 25795 | 108.34 ms |

During the experiment, a process snapshot showed three Java processes consuming
approximately 238%, 178% and 118% CPU, as well as desktop activity. Neither an
improvement nor a regression can be reliably attributed to the candidate from
these contaminated measurements. No worker/GC winner is selected. No deployment
default is changed. Raw local logs: `/tmp/neton-perf-scratch-ab/`.

## Reproducible full-HTTP harness

`scripts/http-perf-ab.py` records binary/config hashes, environment overrides,
per-round server/load logs, host process snapshots, completed requests, errors,
RPS, p99, process CPU/request and end-of-round RSS (not peak RSS). It alternates
variant and setting order and only stops processes it launched. An occupied port
is an error, not permission to kill an existing service.

After building the example against the local candidate, compare the same binary
with scratch disabled/enabled on an otherwise idle machine:

```sh
python3 scripts/http-perf-ab.py \
  --a /absolute/path/bench.kexe --b /absolute/path/bench.kexe \
  --cwd examples/bench --out /tmp/scratch-ab-clean \
  --a-env HYPER4K_REQUEST_STRING_SCRATCH=0 \
  --b-env HYPER4K_REQUEST_STRING_SCRATCH=1 \
  --workers 4 --heaps 1024 --rounds 6
```

Use `--workers 2 4 8 --heaps 1024` for a worker sweep, then
`--workers 4 --heaps 256 1024 4096` for GC resource profiles. Compare identical
binaries and identical scratch settings in those sweeps. The GC target is twice
the minimum; this varies a profile, not each GC field independently. Tokio's
existing `TOKIO_WORKER_THREADS` is used; no new worker configuration API is needed.

The harness shares a host between wrk and the server and does not reproduce
Arena's gcannon/64-thread topology. A winning local candidate must be confirmed
with the published entry's real workload, errors and tail latency before any
Arena performance claim. Host snapshots are diagnostic evidence, not automatic
proof that a host is idle. No background user process is stopped by the harness.

## Next gates

1. Repeat same-binary on/off comparisons on an idle Linux host; exercise short
   fields, long queries and JSON, not just an isolated decoder.
2. Sweep workers and GC budgets separately. More memory/threads is permissible,
   but only keep configurations that improve throughput without tail failures.
3. Reprofile after each retained change. Do not infer a global GC bottleneck from
   the largest individual symbol or from process CPU not reaching 100% per core.
4. Defer responder-registration elimination until explicit handoff ownership is
   specified: an asynchronous completion can race the Rust callback's return.
   Removing registration early is not a safe allocation-only refactor.

This round does not publish Maven artifacts, alter the Arena PR, enable object
pools, change HTTP/2 windows, or assert that the historical Arena zero is fixed.
