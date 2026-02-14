#!/usr/bin/env bash
# 禁止在 neton-* 框架模块源码中使用 println/print 记录业务/框架日志（规范 Neton-Logging-Spec-v1）。
# 检查范围：neton-core, neton-routing, neton-http, neton-redis, neton-cache, neton-validation, neton-logging, neton-database, neton-security。
# 排除：**/generated/**（KSP 生成代码）。白名单（sink impl 必须输出）：JsonLogger.kt、StdoutSink.kt。examples 不检查。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VIOLATIONS=$(rg 'println\(|print\(' --type-add 'kotlin:*.kt' -t kotlin \
  'neton-core/' 'neton-routing/' 'neton-http/' 'neton-redis/' 'neton-cache/' 'neton-validation/' 'neton-logging/' 'neton-database/' 'neton-security/' \
  -g '!*.md' -g '!README*' -g '!**/generated/**' -g '!**/logging/internal/JsonLogger.kt' -g '!**/logging/internal/StdoutSink.kt' 2>/dev/null || true)
if [ -n "$VIOLATIONS" ]; then
  echo "❌ Neton 日志规范：以下框架模块禁止使用 println/print，请改用 LoggerFactory.get(...).info/warn/error"
  echo "$VIOLATIONS"
  exit 1
fi
echo "✅ neton-core / neton-routing / neton-http / neton-redis / neton-cache / neton-validation / neton-logging / neton-database / neton-security 无 println"
