# Releasing

Every module is published from one macOS host; the MinGW C bridge is cross-compiled with
clang and the msys2 sysroot that Kotlin/Native downloads, so no Windows machine is needed.

## Prerequisites

Publication is not release acceptance. The canonical
[1.0 release gate](https://netonframework.github.io/zh-hans/spec/release-gate)
requires evidence from the exact committed candidate, not an edited developer checkout.
Native macOS, Linux and Windows CI and real Redis/PostgreSQL contracts are necessary;
they do not replace the public reference application's release-binary migration and
two-cycle shutdown/resource-release evidence. A private product is never a prerequisite.

The `postgres-contracts` Backend CI job provisions its own PostgreSQL database and runs
`:neton-database:linuxX64Test` with `NETON_DB_TEST_REQUIRED=true`. This flag makes missing
credentials a failure, not an optional-test pass. The gate only accepts a loopback URI
whose database name begins with `neton_gate_`; use a disposable database with no user data.
It checks numeric decoding, migration replay/checksum drift, commit, nested rollback,
cancellation propagation and overlapping independent transactions. Reports are retained
as an artifact named with the candidate commit. This database suite does **not** prove
application lifecycle shutdown or zero connections after SIGTERM.

The public fixture is [examples/release-gate](examples/release-gate/README.md). The
`Reference release` matrix requires macOS ARM64, Linux x64 and Windows x64. Each builds twice,
migrates an isolated PostgreSQL cluster, and checks two healthy start/graceful-stop cycles and
zero connections before teardown. The
`Neton release gates` aggregate requires this matrix and all framework/service/native jobs on
the same commit; failed or skipped prerequisites cannot pass. Evidence is retained under
the candidate SHA. Each platform needs its own passing evidence; the historical macOS-only
result cannot satisfy the expanded matrix or Maven Central publication acceptance. Never substitute downstream product-specific
build or deployment results for missing reference evidence.

`~/.gradle/gradle.properties`:

```
sonatypeUsername=<Central Portal user token>
sonatypePassword=<Central Portal user token password>
signingInMemoryKey=<base64 PGP secret key>
signingInMemoryKeyPassword=<key passphrase>
```

The signing public key must be on a keyserver (`keyserver.ubuntu.com` or `keys.openpgp.org`).

## Publish

### Candidate API/ABI and generated-code gates

Before any publication, `checkKotlinAbi` compares the compiled APIs of all 17
published Kotlin modules with their committed `api/` dumps. `neton-bom` is
validated as publication metadata, not an executable ABI; the experimental
Geario adapter is excluded from publication. ABI target inference is disabled.
The complete check runs on macOS with Kotlin/Native's bundled Linux cross-toolchain;
foreign-target compilation is not a runtime acceptance claim.

```sh
python3 tools/ci/verify_abi_policy.py
./gradlew -Pnetonstream.local=false -Phyper4k.local=false checkKotlinAbi
./gradlew -Pnetonstream.local=false -Pneton.database.driver=mysql :neton-database:checkKotlinAbi
./gradlew -Pnetonstream.local=false -Pneton.database.driver=sqlite :neton-database:checkKotlinAbi
```

`updateKotlinAbi` is a developer review operation, never a CI repair step. Review
signature changes and downstream consumers before updating a dump. The initial
beta25 snapshots establish a candidate comparison point, not a declaration that
1.0 is ready or a promise that every beta signature will survive stable review.
No filters hide currently public implementation classes from the comparison.
The database policy rejects raw sqlx4k types and forbidden legacy persistence
APIs. Before this baseline, the unused public raw-executor accessor was removed;
DDL now uses the current DbContext and has a real transaction-rollback contract.

The [KSP incremental fixture](examples/ksp-incremental/README.md) separately
requires clean/no-op/add/modify/delete builds and Native execution. Linux ARM64
is cross-built on a supported compiler host and executed on an ARM64 runner;
cross-build evidence alone is explicitly incomplete. A failed/skipped platform
blocks the release aggregate. All of this is independent of downstream products.

Before release, disable source substitution and run the same tests against the exact
published dependencies: `-Pnetonstream.local=false -Phyper4k.local=false`.
Publishing tasks reject source substitution, including Maven Local and staging output.
Do not substitute a sibling checkout to hide an unavailable dependency version.

Development CI checks out revisions from `tools/ci/protocol-sources.lock` using
`bash tools/ci/checkout-protocols.sh`. The script never resets existing checkouts.
Run source integration tests with `-Pnetonstream.local=true
-Pnetonstream.local.openssl=true -PnativeTargets=all`; declaring all OpenSSL target
variants does not require compiling all targets when only host test tasks are requested.
Source-stack test success is not evidence that a Central-only consumer can build.

Use the Central Portal bundle upload. Do **not** use the OSSRH staging endpoint
(`publishAllPublicationsToSonatypeCentralRepository`): that API keys its implicit staging
repository by client IP, and an exit-IP change during the seven-minute upload (a TUN-mode
proxy failing over, for example) silently splits the release across two staging repositories.
The 1.0.0-beta1 release was published in two halves for exactly this reason. The bundle
upload is a single request and cannot split.

```bash
# 1. Build, sign and lay out every publication under build/staging-repo
./gradlew -Pnetonstream.local=false -Phyper4k.local=false publishAllPublicationsToStagingLocalRepository

# 2. Bundle it (repository-level metadata is not part of a bundle)
cd build/staging-repo
find com -name 'maven-metadata.xml*' -delete
zip -qr ../neton-<version>.zip com
cd -

# 3. Upload with USER_MANAGED so validation can be inspected before anything goes live
TOKEN=$(printf '%s:%s' "$SONATYPE_USER" "$SONATYPE_PASS" | base64)
curl -X POST -H "Authorization: Bearer $TOKEN" \
     -F "bundle=@build/neton-<version>.zip" \
     "https://central.sonatype.com/api/v1/publisher/upload?name=neton-<version>&publishingType=USER_MANAGED"
# → prints a deployment id

# 4. Poll until VALIDATED, then publish
curl -X POST -H "Authorization: Bearer $TOKEN" "https://central.sonatype.com/api/v1/publisher/status?id=<deployment id>"
curl -X POST -H "Authorization: Bearer $TOKEN" "https://central.sonatype.com/api/v1/publisher/deployment/<deployment id>"

# 5. Confirm every coordinate
curl -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/published?namespace=com.netonstream&name=neton-core&version=<version>"
```

Publishing to Central is irreversible. Check the validation result before step 4.

## After publishing

- Tag: `git tag -a v<version> -m "Neton <version>" && git push origin v<version>`
- Verify from a clean project that resolves only from `mavenCentral()`:
  `implementation("com.netonstream:neton:<version>")` plus one versionless module.
