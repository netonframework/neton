# KSP Native incremental release gate

This is a public Framework-only fixture. It needs no running database and no
Application or product module. `AnchorTable` and each temporary table must compile
and link using the real database API and the real KSP processor.

Run on a fresh checkout on the corresponding native host:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/ci/ksp_incremental_gate.py \
  --target macosArm64 --output /new/absolute/evidence-directory
```

Targets: `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64`. The database does not
publish an Intel Mac variant. Kotlin/Native cannot run its compiler on Linux ARM64:
that target builds on Linux x64 with `--defer-native-execution`, then a native ARM64
job uses `--run-artifact` to verify SHA, all five binary hashes, and execution.
Cross-compilation alone leaves `passed: false`; both jobs are mandatory.
The runner retains each build log and an evidence
manifest. Its external test source directory is created for this run only; tracked
sources are not edited. One output tree is reused for clean/no-op/add/modify/delete;
build-cache restoration is disabled, and no clean occurs between stages.

Generated files are outputs, never edited. Deletion must remove generated entity
files and any aggregate references. Each stage executes the linked Native binary.
An initial ABI dump is a review candidate, not an automatic stable API approval.
