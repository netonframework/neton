#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
parent="${NETON_PROTOCOL_SOURCES_DIR:-$(dirname "$root")}"
# Never reset an existing developer checkout. CI starts with empty siblings.
while read -r name revision; do
    destination="$parent/$name"
    if [[ -e "$destination" ]]; then
        actual="$(git -C "$destination" rev-parse HEAD)"
        [[ "$actual" == "$revision" ]] || { echo "$name: expected $revision, found $actual" >&2; exit 1; }
        [[ -z "$(git -C "$destination" status --porcelain)" ]] || { echo "$name: working tree is dirty" >&2; exit 1; }
    else
        git init "$destination"
        git -C "$destination" remote add origin "https://github.com/netonframework/$name.git"
        git -C "$destination" fetch --depth=1 origin "$revision"
        git -C "$destination" checkout --detach FETCH_HEAD
    fi
    echo "$name $(git -C "$destination" rev-parse HEAD)"
done < "$root/tools/ci/protocol-sources.lock"
