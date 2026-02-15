# Phase 1 真实数据库集成测试

当前所有单元测试均在「SQL 字符串 / AST 层」验证，不连接真实数据库。  
要确认 **占位符顺序、PG $1 / MySQL ?、LIMIT 语法、AutoFill 写入、SoftDelete 过滤** 在真实环境中正确，需要跑一次 **真实 PostgreSQL（建议优先）或 MySQL** 的集成测试。

## 为何不放在仓库内自动跑？

- `neton-database` 为 Kotlin Multiplatform（当前目标：macOS/Linux/Windows Native），**未包含 JVM 目标**。
- 若为集成测试单独加入 JVM + Testcontainers，需同时为 `neton-logging`、`neton-core` 等增加 JVM 与对应 `expect/actual`，改动面较大。
- 因此采用 **「本地/CI 自备 PostgreSQL，按文档跑一遍」** 的方式做终审级验证。

## 推荐方式：本地用 Docker 起 PG，跑验收脚本

### 1. 启动 PostgreSQL

**方式一（推荐）**：使用脚本

```bash
./scripts/start-pg-for-integration-test.sh
```

**方式二**：手动运行

```bash
docker run -d --name neton-pg -e POSTGRES_USER=u -e POSTGRES_PASSWORD=p -e POSTGRES_DB=phase1test -p 5432:5432 postgres:16-alpine
```

### 2. 配置并启动应用（或独立验收工程）

在应用或示例工程中配置数据库为上述实例，例如：

- **URI**：`postgresql://u:p@localhost:5432/phase1test`
- **Driver**：`POSTGRESQL`

然后执行一次「Phase 1 验收流程」：

1. **建表**：包含 `id`, `name`, `status`, `deleted`, `created_at`, `updated_at` 等字段。
2. **插入 3 条**（依赖 @AutoFill 填 `created_at`/`updated_at`）。
3. **软删 1 条**：`destroy(id)` → 该行 `deleted = true`。
4. **query { }.list()**：应只剩 2 条（未删）。
5. **query { }.page(1, 10)**：`page.total == 2`，且 `page.total == query { }.count()`。
6. **many(ids)**：用未删的 2 个 id，应返回 2 条。
7. **existsWhere**：按 `status` 等条件验证 true/false。

若以上全部通过，可认为：**占位符、LIMIT、SoftDelete、AutoFill 在真实 PG 上行为正确**。

### 3. MySQL 可选

若需同时验证 MySQL，可再起一个 MySQL 容器，用相同流程跑一遍（注意 LIMIT 语法为 `LIMIT offset, limit`，由 Dialect 已封装）。

## CI 建议

- 在 CI 中增加一个 job：**启动 PostgreSQL 服务**（或使用托管 PG），配置 `NETON_PG_URI`（或等价配置），运行上述验收流程（可由示例工程或单独小脚本执行）。
- 通过后即可视为：**Neton Database Phase 1 = Production Ready（PG）**；若再跑通 MySQL，可打 `database-phase1-stable` tag。

## 当前仓库内已有验证

- **CountSelectWhereContractTest**：同一 `QueryAst` 下 `buildCount` 与 `buildSelect` 的 WHERE 及参数一致 → 保证「同 where 下 page().total == count()」在 SQL 层成立。
- **Phase1DemoTest**：many(ids) 空集 → 1=0、whenPresent/whenNotBlank/whenNotEmpty、listPage 与 count 同 WHERE。
- **NormalizeForSoftDeleteTest**：SoftDelete 注入逻辑正确。

以上均为「不连库」的单元/契约测试；**真实 PG/MySQL 跑通一次** 是终审条件。

---

## 可选：在仓库内用 Testcontainers 自动跑

若希望集成测试在仓库内由 JVM + Testcontainers 自动执行，需要：

1. 为 `neton-logging`、`neton-core`、`neton-database` 增加 `jvm()` 目标；
2. 为 `neton-logging` 的 `expect` 声明提供 JVM 的 `actual` 实现；
3. 在 `neton-database` 的 `jvmTest` 中加入 Testcontainers 依赖并编写 `Phase1RealDbIntegrationTest`（建表、插 3 条、软删 1 条、query/page/many/existsWhere 断言）。

当前未采用该方案以控制改动范围；若后续为脚手架或 CI 需要，可按上述步骤补齐。
