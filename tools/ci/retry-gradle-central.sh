#!/usr/bin/env bash
# Retry a Gradle invocation only when Maven Central throttles dependency resolution before tasks begin.
set -euo pipefail
if (( $# < 2 )); then
  echo "Usage: retry-gradle-central.sh LOG_PREFIX COMMAND [ARGS...]" >&2
  exit 2
fi

prefix="$1"
shift
for attempt in 1 2 3; do
  log="$prefix-$attempt.log"
  if "$@" 2>&1 | tee "$log"; then
    exit 0
  else
    status=$?
  fi

  if [[ "$attempt" == 3 ]] || grep -q '^> Task ' "$log" ||
     ! grep -Eq 'Could not resolve (all )?(artifacts|dependencies)|Repository MavenRepo is disabled' "$log" ||
     ! grep -Eq "Could not (GET|HEAD) 'https://repo\.maven\.apache\.org/maven2/[^']+'\. Received status code 429 from server" "$log"; then
    exit "$status"
  fi
  echo "Maven Central throttled dependency resolution before any task. Retrying after $((attempt * 30))s." >&2
  sleep "$((attempt * 30))"
done
