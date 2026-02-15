# Neton Database 模块 - 能力与对比

## 定型 API（业务层）

- **UserTable.get(id)** — 主键查询
- **UserTable.destroy(id)** — 按 id 删除
- **UserTable.update(id) { name = x; email = y }** — mutate 风格，KSP 内部生成 copy，业务层直接赋值
- **UserTable.query { where { } }.list()** / **.page(1, 20)**
- **user.save()** / **user.delete()**

无 Table/Store/Impl 暴露，KSP 生成扩展。

## 与常见框架对比（简要）

| 维度     | Neton Database     | Spring Data JPA | jOOQ / MyBatis Plus |
|----------|--------------------|------------------|----------------------|
| 类型安全 | ✅ KProperty1 DSL  | ❌ 方法名/字符串 | ⚠️ 部分              |
| API 长度 | ✅ get/destroy/update | ❌ findById 等   | ⚠️ 各异              |
| 暴露层   | ✅ 仅 Entity API   | ❌ Repository/EntityManager | ❌ Mapper/DSL 暴露 |
| 底层     | sqlx4k，可替换    | JPA 实现        | JDBC/实现            |

设计目标：Laravel 手感 + Kotlin 类型安全 + 轻量、可冻结。

详见 [neton-database-query-dsl-v2.md](../../../neton-docs/neton-database-query-dsl-v2.md)。
