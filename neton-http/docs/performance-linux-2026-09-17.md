# Linux follow-up: request-string scratch buffer

## Scope and controls

This is an isolated small-host experiment, not an HttpArena score or a test of
64-thread scalability. The supplied Fedora 44 KVM guest exposes two logical
CPUs and 1664 MiB RAM. Existing services were left running. Both the load client
and server ran in a temporary network namespace with only loopback enabled;
no benchmark listener was exposed to the host's public network.

The same release executable was used in both variants:

```
SHA256 1ec1fcc7f9af969f948a316c4ee8c081b864b038d7ec382cbb86e19b5931fbaf
```

It was cross-compiled from the current local Neton example against local Hyper4k,
not downloaded from Maven. Neton HEAD at build time was `9a4f0f4`; Hyper4k HEAD
was `0189865` plus the uncommitted scratch-buffer experiment. This is not a
beta18-versus-beta19 comparison. The latter version has not been released.

Scratch comparison: A sets `HYPER4K_REQUEST_STRING_SCRATCH=0`, B sets it to `1`.
All other inputs are identical: WARN logging, 2 Tokio workers, GC minimum/target
256/512 MiB, wrk 1 thread and 128 connections, 3-second warmup and 10-second
measurement. Four rounds alternate AB, BA, AB, BA. Each variant starts a fresh
server. The tables report medians of individual runs, not aggregated percentiles.

## Scratch results

| Request | Off RPS | On RPS | Difference | Off CPU/request | On CPU/request |
| --- | ---: | ---: | ---: | ---: | ---: |
| `/sum?a=3&b=4` | 44,711 | 44,682 | -0.06% | 27.853 us | 27.934 us |
| Same request plus 512 ASCII padding bytes in query | 34,638 | 36,406 | +5.10% | 40.959 us | 37.780 us |

For the long query, every on-run's RPS exceeded every off-run's RPS, and median
server CPU/request fell approximately 7.76%. This supports retaining the
candidate for longer request fields. It does not establish a baseline benefit:
the short-query comparison is effectively unchanged.

Long-query p99 medians were 23.315 ms off and 23.605 ms on; ranges overlap and one
on-run reached 32.99 ms. Do not claim a tail-latency improvement. End-of-run RSS
was approximately 247 MiB for both variants. The returned String still allocates;
the experiment only reuses its temporary decoding byte buffer.

## Worker sweep

Scratch disabled, GC minimum/target 256/512 MiB; four repetitions per setting,
with setting order reversed in the second round. A/B here are identical settings,
not different code paths.

| Tokio workers | Median RPS | Median CPU/request | Median per-run p99 |
| --- | ---: | ---: | ---: |
| 1 | 27,432 | 30.264 us | 22.225 ms |
| 2 | 44,869 | 27.897 us | 37.255 ms |
| 4 | 44,298 | 28.517 us | 35.875 ms |

Two workers outperformed one for throughput in this setup. Four did not improve
on two. This does not set a universal worker count: the load generator shares
the guest's two CPUs, and its CPU use can constrain the server. The lower
throughput setting also had lower measured p99, so throughput and latency must
not be conflated. No framework worker default is changed.

## GC resource-profile sweep

Two workers, scratch disabled, same short query. Both the minimum and target
change together; this does not isolate each GC setting. Four runs per profile:

| GC minimum/target | Median RPS | Median CPU/request | Median per-run p99 | End RSS median |
| --- | ---: | ---: | ---: | ---: |
| 128/256 MiB | 44,470 | 27.946 us | 19.165 ms | 131.4 MiB |
| 512/1024 MiB | 44,984 | 27.781 us | 55.260 ms | 477.5 MiB |

The larger profile's median throughput was only 1.16% higher, with overlapping
run ranges, while its p99 was higher in all four runs. This does not support
enabling the larger profile as a performance improvement here. More memory is
allowed by the objective, but this specific use of it has not earned adoption.
No claim about the cause of the latency change is made without GC timing data.

## Reproduction and limits

Use `scripts/http-perf-ab.py` with `--threads 1 --workers 2 --heaps 256
--duration 10 --rounds 4` and the variant environment overrides above. The long
query adds `&padding=` followed by 512 `x` characters. For the worker sweep, use
`--workers 1 2 4 --rounds 2`, with scratch disabled for both labels.

The script records process CPU, RPS, p99, end RSS, host process snapshots and
Linux swap/steal counter deltas. Neither an end RSS sample nor zero swap activity
proves a global absence of memory pressure. CPU/request is process CPU divided
by completed requests in the measurement window, not an environment-independent
efficiency score. wrk's latency is not a replacement for Arena's open-loop latency
profiles or gcannon throughput workload.

All 36 measured runs completed, totaling 14,688,858 requests; wrk reported no
socket errors or non-success HTTP statuses. Readiness checks verified body `7`;
wrk did not individually validate every response body. Measured windows recorded
zero swap-in pages, swap-out pages and steal ticks. All test server processes
exited after their runs, and the pre-existing listeners remained unchanged.

Per-run measurements and binary/config identities are preserved in
`performance-linux-2026-09-17-results.json`. Full local logs are in
`/tmp/neton-linux-perf-results-20260917/`; remote reproduction files remain in
`/tmp/neton-perf-20260917/`. No password is stored in these files.

Keep scratch opt-in until the real Arena workload and larger Linux systems are
validated. No Maven publishing, PR changes, server-wide tuning, firewall changes,
or termination of pre-existing services is part of this experiment.
