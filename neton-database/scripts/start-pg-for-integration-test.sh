#!/usr/bin/env bash
# 启动 PostgreSQL 容器，用于 Phase 1 集成测试验收
# 使用方式：./scripts/start-pg-for-integration-test.sh
# 连接 URI：postgresql://u:p@localhost:5432/phase1test

set -e

CONTAINER_NAME="neton-pg"
IMAGE="postgres:16-alpine"
PORT=5432
USER="u"
PASS="p"
DB="phase1test"

# 若已存在同名容器，先停并删除
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "停止并删除已有容器 ${CONTAINER_NAME}..."
  docker stop "$CONTAINER_NAME" 2>/dev/null || true
  docker rm "$CONTAINER_NAME" 2>/dev/null || true
fi

echo "启动 PostgreSQL 容器 ${CONTAINER_NAME}..."
docker run -d \
  --name "$CONTAINER_NAME" \
  -e POSTGRES_USER="$USER" \
  -e POSTGRES_PASSWORD="$PASS" \
  -e POSTGRES_DB="$DB" \
  -p "$PORT:5432" \
  "$IMAGE"

echo "等待 PostgreSQL 就绪..."
sleep 3
until docker exec "$CONTAINER_NAME" pg_isready -U "$USER" -d "$DB" 2>/dev/null; do
  sleep 1
done

echo ""
echo "✅ PostgreSQL 已启动"
echo "   URI: postgresql://${USER}:${PASS}@localhost:${PORT}/${DB}"
echo ""
echo "停止容器：docker stop ${CONTAINER_NAME}"
echo "删除容器：docker rm ${CONTAINER_NAME}"
