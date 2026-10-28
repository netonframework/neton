# Public reference release fixture

This is a Framework integration fixture, not an Application distribution or a product.
It has no business-module or private-service dependency. Normative acceptance rules live in
[neton-docs](https://netonframework.github.io/zh-hans/spec/release-gate).

The `reference` module declares `@Module(migrations = true)` and a constructor-injected
health controller. Gradle embeds the versioned SQL; KSP generates its module manifest.
The normal server path installs `database`, `http`, `routing` and that manifest through
`Neton.run`. Database shutdown belongs to the framework component, not the harness.
Normal startup never creates or migrates schema. `migrate up|status|verify` runs explicitly
and closes its own command-scoped database handle in `finally`.

## Reproduce

Use a fresh checkout of a committed revision on macOS ARM64 (the CI baseline), with JDK 17,
Xcode, Python 3 and PostgreSQL 16 command-line tools. Linux native targets are accepted by
the harness but are not claimed verified by the macOS result. Intel Mac is not a supported
database-driver target. Do not point the gate at an existing database.

```sh
bash tools/ci/checkout-protocols.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/ci -p 'test_reference_gate.py'
python3 tools/ci/reference_gate.py --target macosArm64 \
  --pg-bin /absolute/path/to/postgresql/bin --output /new/absolute/evidence-directory
```

The checkout script refuses dirty/mismatched siblings. To isolate protocol sources as well,
set `NETON_PROTOCOL_SOURCES_DIR` to a new directory; the harness passes this location to Gradle.
No developer checkout is reset. Build outputs and source commits must not be shared with another
active build. This fixture uses the explicitly pinned source integration stack; it does not replace
the separate Maven Central consumer/publication verification.

The harness builds a **release** executable twice (second pass without clean), records the
candidate/dependency commits and binary SHA-256, creates a temporary loopback-only PostgreSQL
cluster, and runs `up`, repeated `up`, `status`, `verify`. It then performs two cycles, each with
semantic HTTP READY checks for at least 60 seconds, SIGTERM, exit 0 within 30 seconds, closed HTTP
port and zero application connections checked via the maintenance database **before teardown**.
It verifies there were active database connections while serving, so a zero count cannot pass
merely because the probe never used the pool. Only the owned processes and cluster are cleaned up.

The output directory contains build/migration/process/database logs and `evidence.json`.
Failures retain `passed: false`; a retry is a separate run, not a replacement for failed evidence.
CI uploads these artifacts under the candidate commit and the aggregate `Neton release gates`
check rejects any failed or skipped prerequisite. No release is published by this workflow.
