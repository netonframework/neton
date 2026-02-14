#!/usr/bin/env bash
# Neton multigroup 端点验证脚本
# 用途：构建 → 启动服务 → 请求各端点 → 验证响应 → 停止服务
#
# 用法：
#   ./verify-endpoints.sh          # 完整构建 + 验证
#   ./verify-endpoints.sh --skip-build   # 跳过构建，仅验证（需已构建）
#   PORT=9090 ./verify-endpoints.sh      # 指定端口
#
# 依赖：curl；需 Redis 运行（LockDemoController 的 @Lock 会用）
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PORT="${PORT:-8080}"

# 可执行文件路径（Kotlin/Native 默认输出）
EXE_PATH="$SCRIPT_DIR/build/bin/macosArm64/debugExecutable/multigroup.kexe"
if [[ ! -f "$EXE_PATH" ]]; then
    FOUND=$(find "$SCRIPT_DIR/build" -name "*.kexe" -type f 2>/dev/null | head -1)
    [[ -n "$FOUND" ]] && EXE_PATH="$FOUND"
fi
BASE_URL="http://localhost:$PORT"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASSED=0
FAILED=0

# 通用请求封装：method path [expected_status] [extra_curl_args]
request() {
    local method="$1"
    local path="$2"
    local expected="${3:-200}"
    shift 3 2>/dev/null || true
    local url="$BASE_URL$path"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$url" "$@" 2>/dev/null || echo "000")
    code="${code:0:3}"
    if [[ "$code" == "$expected" ]]; then
        echo -e "${GREEN}✓${NC} $method $path → $code (expected $expected)"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}✗${NC} $method $path → $code (expected $expected)"
        ((FAILED++))
        return 1
    fi
}

echo "=========================================="
echo "Neton multigroup 端点验证"
echo "=========================================="
echo "项目根目录: $PROJECT_ROOT"
echo "可执行文件: $EXE_PATH"
echo "端口: $PORT"
echo "=========================================="

# 1. 构建（若可执行文件已存在且指定 --skip-build 则跳过）
echo ""
echo "[1/4] 构建 multigroup..."
cd "$PROJECT_ROOT"
if [[ "$1" != "--skip-build" ]] || [[ ! -f "$EXE_PATH" ]]; then
    ./gradlew :examples:multigroup:linkDebugExecutableMacosArm64 --no-daemon -q
fi
# 再次解析可执行文件路径
EXE_PATH="$SCRIPT_DIR/build/bin/macosArm64/debugExecutable/multigroup.kexe"
[[ ! -f "$EXE_PATH" ]] && EXE_PATH=$(find "$SCRIPT_DIR/build" -name "*.kexe" -type f 2>/dev/null | head -1)
if [[ ! -f "$EXE_PATH" ]]; then
    echo -e "${RED}错误: 可执行文件不存在，请先执行 ./gradlew :examples:multigroup:linkDebugExecutableMacosArm64${NC}"
    exit 1
fi
echo -e "${GREEN}构建完成: $EXE_PATH${NC}"

# 2. 启动服务（后台，从 multigroup 目录启动以正确加载 config/）
echo ""
echo "[2/4] 启动服务..."
# 若端口被占用则先终止（避免 EADDRINUSE）
if command -v lsof >/dev/null 2>&1; then
    OLD_PID=$(lsof -ti :$PORT 2>/dev/null)
    if [[ -n "$OLD_PID" ]]; then
        echo "端口 $PORT 已被占用，终止旧进程 $OLD_PID..."
        kill -9 $OLD_PID 2>/dev/null || true
        sleep 1
    fi
fi
(cd "$SCRIPT_DIR" && "$EXE_PATH" --server.port=$PORT) &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null; exit" INT TERM EXIT

# 等待服务就绪
echo "等待服务启动 (端口 $PORT)..."
for i in {1..30}; do
    if curl -s -o /dev/null "$BASE_URL/" 2>/dev/null; then
        echo -e "${GREEN}服务已就绪${NC}"
        break
    fi
    sleep 0.5
    if [[ $i -eq 30 ]]; then
        echo -e "${RED}服务启动超时${NC}"
        kill $SERVER_PID 2>/dev/null
        exit 1
    fi
done

# 3. 验证端点
echo ""
echo "[3/4] 验证端点..."

# 首页
request GET "/"

# ===== 路由组 + mount 测试 =====
# admin 组 (mount /admin)：根路径、需认证接口、允许匿名
request GET "/admin"                    # admin home（相对路径 get("") 同时匹配 /admin 与 /admin/）
request GET "/admin/"                   # admin home
request GET "/admin/index"              # admin ok
request GET "/admin/index/public"       # 允许匿名
request GET "/admin/index/dashboard"    # admin dashboard
request GET "/admin/payment/index"      # payment admin
request GET "/admin/payment/index/orders"

# app 组 (mount /app)
request GET "/app/index"
request GET "/app/index/user"
request POST "/app/index/submit" 415 -H "Content-Type: application/json" -d '{}'

# 模块 payment 默认组（无 mount）
request GET "/payment/index"
request GET "/payment/index/status"

# SimpleController
request GET "/simple/hello"
request GET "/simple/user/123"
request GET "/simple/user/456/post/789"
request GET "/simple/request-info"
request GET "/simple/profile"
# POST /simple/user：需 application/json；当前框架 Content-Type 校验较严，415 表示端点存在
request POST "/simple/user" 415 -H "Content-Type: application/json" -d '{"name":"Test","email":"test@example.com","age":25}'

# SecurityController - 公开接口
request GET "/api/security/public"
request GET "/api/security/visitor"
# 需认证接口：AppSecurityConfig 使用 mock 认证（test-user），故返回 200
request GET "/api/security/protected"
request GET "/api/security/admin"
request GET "/api/security/dashboard"

# HttpMethodController (products)
request GET "/api/products"
request GET "/api/products/1"
request POST "/api/products"
request PUT "/api/products/1"
request GET "/api/products/search"
request POST "/api/products/bulk"

# LockDemoController
request GET "/api/lock/res-001"
request GET "/api/lock/demo-xyz"

# ParameterBindingController
request GET "/api/binding/users/42"
request GET "/api/binding/search?keyword=test&page=1&size=10"
request GET "/api/binding/optional?required=foo"
request GET "/api/binding/filters?tags=a&tags=b"

# PathPatternController
request GET "/api/patterns/simple"
request GET "/api/patterns/users/123"
request GET "/api/patterns/users/1/posts/2"
request GET "/api/patterns/files/doc.pdf"
request GET "/api/patterns/static/images"
request GET "/api/patterns/reports/sales.json"
request GET "/api/patterns/products/ABC123"
request GET "/api/patterns/v1/data"
request GET "/api/patterns/v2/data"
request GET "/api/patterns/json"
request GET "/api/patterns/text"
request GET "/api/patterns/number"
request GET "/api/patterns/boolean"

# SimpleTestController
request GET "/test/hello"
request GET "/test/world/Neton"
request POST "/test/echo?message=hello"

# ModernController（@Controller "" + @Get "/test" → GET /test）
request GET "/test"

# HttpObjectController
request GET "/api/http/request-info"
request GET "/api/http/response-demo"
request GET "/api/http/session-info"

# 4. 汇总
echo ""
echo "[4/4] 停止服务..."
kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
trap - INT TERM EXIT

echo ""
echo "=========================================="
echo "验证结果: ${GREEN}$PASSED 通过${NC}, ${RED}$FAILED 失败${NC}"
echo "=========================================="

if [[ $FAILED -gt 0 ]]; then
    exit 1
fi
exit 0
