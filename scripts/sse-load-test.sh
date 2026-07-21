#!/usr/bin/env bash
# SSE 长流并发压测：N 路并发 curl 消费长流，采样服务进程 RSS。
# 用法: ./scripts/sse-load-test.sh [并发数=200] [每流事件数=300] [事件间隔ms=100]
set -euo pipefail
CONC="${1:-200}"; COUNT="${2:-300}"; DELAY="${3:-100}"
BIN="examples/sse-demo/build/bin/macosArm64/releaseExecutable/sse-demo.kexe"

[ -x "$BIN" ] || { echo "先构建: ./gradlew :examples:sse-demo:linkReleaseExecutableMacosArm64"; exit 1; }

(cd examples/sse-demo && "../../$BIN" >/tmp/sse-load-server.log 2>&1) & SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT
sleep 1
# 找到真实的 kexe 子进程 pid（subshell 包了一层）
KPID=$(pgrep -f "sse-demo.kexe" | head -1)

echo "server pid=$KPID conc=$CONC count=$COUNT delayMs=$DELAY"
( while kill -0 "$KPID" 2>/dev/null; do
    echo "$(date +%T) RSS_KB=$(ps -o rss= -p "$KPID" | tr -d ' ')"
    sleep 2
  done ) & MON=$!

START=$(date +%s)
if seq 1 "$CONC" | xargs -P "$CONC" -S 1024 -I{} \
  curl -sN --max-time 300 "http://localhost:8080/stream?count=$COUNT&delayMs=$DELAY" -o /dev/null; then
  echo "ALL_STREAMS_OK elapsed=$(( $(date +%s) - START ))s"
else
  echo "SOME_STREAMS_FAILED"
fi
kill $MON 2>/dev/null || true
if kill -0 "$KPID" 2>/dev/null; then echo "SERVER_ALIVE"; else echo "SERVER_CRASHED"; fi
pkill -f "sse-demo.kexe" 2>/dev/null || true
