#!/usr/bin/env bash
# Verify a packaged engine dependency, not a composite-build substitution.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="${HYPER4K_DIR:-$root/../hyper4k}"
case "$(uname -s):$(uname -m)" in
    Darwin:arm64) target=macosArm64; publication=MacosArm64 ;;
    Darwin:x86_64) target=macosX64; publication=MacosX64 ;;
    *) printf '%s\n' 'This artifact smoke test currently requires a macOS host.' >&2; exit 2 ;;
esac
if [[ ! -f "$engine/gradlew" ]]; then
    printf 'Missing Hyper4k checkout: %s\n' "$engine" >&2
    exit 2
fi
engine="$(cd "$engine" && pwd)"
jvm="-Dorg.gradle.jvmargs=${VERIFY_JVM_ARGS:--Xmx3g -XX:MaxMetaspaceSize=1g}"

# This repository is a local directory. Nothing is uploaded or installed into
# ~/.m2; existing released versions are not overwritten.
(cd "$engine" && ./gradlew \
    publishKotlinMultiplatformPublicationToStagingLocalRepository \
    "publish${publication}PublicationToStagingLocalRepository" \
    --console=plain "$jvm")

cd "$root"
./gradlew ":neton-http-hyper4k:${target}Test" \
    -Phyper4k.local=false \
    "-Phyper4k.repository=$engine/build/staging-repo" \
    --console=plain "$jvm"
