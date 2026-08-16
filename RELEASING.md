# Releasing

Every module is published from one macOS host; the MinGW C bridge is cross-compiled with
clang and the msys2 sysroot that Kotlin/Native downloads, so no Windows machine is needed.

## Prerequisites

`~/.gradle/gradle.properties`:

```
sonatypeUsername=<Central Portal user token>
sonatypePassword=<Central Portal user token password>
signingInMemoryKey=<base64 PGP secret key>
signingInMemoryKeyPassword=<key passphrase>
```

The signing public key must be on a keyserver (`keyserver.ubuntu.com` or `keys.openpgp.org`).

## Publish

Use the Central Portal bundle upload. Do **not** use the OSSRH staging endpoint
(`publishAllPublicationsToSonatypeCentralRepository`): that API keys its implicit staging
repository by client IP, and an exit-IP change during the seven-minute upload (a TUN-mode
proxy failing over, for example) silently splits the release across two staging repositories.
The 1.0.0-beta1 release was published in two halves for exactly this reason. The bundle
upload is a single request and cannot split.

```bash
# 1. Build, sign and lay out every publication under build/staging-repo
./gradlew publishAllPublicationsToStagingLocalRepository

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
