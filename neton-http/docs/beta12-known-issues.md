# beta12 known issues

Registered from code review, not from a benchmark. beta12 shipped before the
static-file and TLS review was fully delivered; "Linux smoke passed" is not full
acceptance. These are being fixed on a separate branch; beta12 and the open PR
stay unchanged so the version under comparison is not disturbed.

## TLS lifecycle and downgrade

- **No unified listener lifecycle.** The arena entry starts the extra listeners
  on independent `CoroutineScope`s with no owner, no readiness gate and no
  shutdown. Required ports should all be listening before READY; a failure should
  tear down the ones already up, in reverse; exit should stop and join all. —
  *pending. The benchmark harness runs the process to completion, so fire-and-
  forget functions there, but it is not a clean lifecycle.*
- **`http {}` / application.conf → TLS wiring.** — *fixed: `http { tls { } }`
  populates HttpConfig.tls, and application.conf `[http.tls]` (certificatePath,
  privateKeyPath, alpnProtocols) is resolved with precedence over the DSL. Verified
  end to end on Linux: a config-file section alone brings up TLS 1.3 on the primary
  listener with no code or env change.*
- **Ktor adapter silently serves cleartext when TLS is configured.** It ignored
  `serverConfig.tls`. — *fixed: it now fails to start when TLS is set.*

## Static file contract

- **Accept-Encoding q-values ignored.** `gzip;q=0` was treated as "gzip
  accepted". — *fixed.*
- **HEAD did not select the pre-compressed variant**, so HEAD metadata (ETag,
  Content-Encoding) disagreed with the matching GET. — *fixed.*
- **Large files are read whole into memory.** — *fixed: files over 256 KiB stream
  from disk in 64 KiB chunks through the engine streaming path (chunked / h2 DATA),
  so memory is bounded and a disconnect stops the read. Verified over real TLS.*
- **TOCTOU between realpath check and reopen.** The escape check realpath'd the
  target, then the read reopened by path. — *narrowed: read from the validated
  real path; residual symlink-swap race documented.*
- **Cache identity is (size, mtime-ms).** A same-length, same-millisecond
  in-place overwrite is not detected. Atomic replace is safe. — *documented in
  static-files.md; replacement test added.*

## Logging / usability

- **Default (INFO) access logging writes a structured line to stdout synchronously
  per request, and it dominates the request.** On a 2-core box the default-config
  server fell to ~630 rps (~3 ms/request) purely from this; WARN removes it. The
  benchmark runs WARN so its scores are unaffected, but this is a real
  out-of-the-box experience problem: a user running at INFO pays it. The access
  log should be off the request hot path (async/batched) or clearly gated. —
  *registered; not yet fixed.*
