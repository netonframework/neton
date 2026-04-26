# Neton Database 模块

🚀 **全新架构的数据库模块 - 面向未来的企业级数据访问层**

## ✨ 核心特性

### 🎯 零配置使用
- **@Entity + @Id** 即可使用，无需复杂配置
- **自动 CRUD** 操作，开箱即用
- **智能 Table** 自动适配，类型安全

### 🔄 数据库支持（v1）
- **sqlx4k SQLite** - 主路径，内存或文件
- **PostgreSQL/MySQL** - 依赖已内置，在 `database.conf` 中配置 `driver` 和 `uri` 即可

### 🛡️ 类型安全 DSL
- **编译时检查** - 杜绝运行时 SQL 错误
- **链式查询** - 直观的查询构建
- **智能推断** - IDE 自动补全支持

### ⚡ 现代化架构
- **URI 配置** - 统一连接格式
- **组件化设计** - 可插拔驱动架构
- **Kotlin Native** - 原生性能

## 🏗️ 架构设计

```
neton-database/
├── api/                    # 统一 Table 接口
├── annotations/            # 实体注解
├── config/                 # TOML 配置
├── adapter/sqlx/           # sqlx4k 适配器（主路径）
├── query/                  # Query DSL、QueryRuntime
└── 示例见 examples/mvc
```

### Phase 1 真实数据库验收

单元测试与契约测试不连库；若需用**本机 PostgreSQL** 做一次完整流程验收（建表、插入、软删、query/page、many、existsWhere），见 **[docs/INTEGRATION_TEST.md](docs/INTEGRATION_TEST.md)**。

## 🚀 快速开始

### 1. 定义实体模型

```kotlin
@Serializable
@Table("users")
data class User(
    @Id val id: Long? = null,
    val name: String,
    val email: String,
    val status: Int,
    val age: Int
)
```

### 2. 配置数据库连接

```toml
# config/database.conf
[default]
driver = "SQLITE"          # SQLITE | POSTGRESQL | MYSQL
uri = "sqlite://data/myapp.db"  # 或 sqlite::memory: 用于开发测试
debug = true
maxConnections = 10
connectionTimeout = 30000
```

**支持的 URI 格式：**
- SQLite：`uri = "sqlite://data/myapp.db"` 或 `uri = "sqlite::memory:"`（内存）
- PostgreSQL：`uri = "postgresql://user:password@localhost:5432/mydb"`
- MySQL：`uri = "mysql://user:password@localhost:3306/mydb"`

### 3. 使用 install DSL

```kotlin
import neton.core.Neton
import neton.http.http
import neton.routing.routing
import neton.database.database

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        routing { }
        database { }  // 配置从 config/database.conf 加载
        onStart { /* UserTable.ensureTable() 等 — 仅 dev/demo 使用，见下方 Schema 演进章节 */ }
    }
}
```

### 4. Table Facade 模式（推荐，IDE 友好）

手写 Table Facade，KSP 只生成内部实现 `XxxTableImpl`：

```kotlin
// model/User.kt — 实体定义
@Table("users")
data class User(@Id val id: Long?, val name: String, val email: String, val status: Int, val age: Int)

// table/UserTable.kt — 手写 Facade（IDE Go to Definition 跳到这里）
package table
import model.User
import model.UserTableImpl
import neton.database.api.Table

object UserTable : Table<User, Long> by UserTableImpl
```

业务代码使用：

```kotlin
import table.UserTable  // 语义清晰：table 层
```

> 也可以不写 Facade，KSP 会直接生成 `UserTable`（向后兼容）。

### 5. 使用 Entity 为中心的 API

实体用 `@Table` + `@Id`，KSP 生成 `object UserTable : Table<User, Long>` 及 `user.save` / `user.delete`：

```kotlin
// 主键查询
val user = UserTable.get(1)

// 条件查询（query { where { } } 为唯一入口，where 内用 ColumnRef）
import neton.database.dsl.ColumnRef
val activeUsers = UserTable.query { where { ColumnRef("status") eq 1 } }.list()
val adults = UserTable.query {
    where { and(ColumnRef("age") gt 18, ColumnRef("status") eq 1) }
    orderBy(ColumnRef("id").desc())
    limitOffset(20, 0)
}.list()

// 分页
val page = UserTable.query { where { ColumnRef("status") eq 1 } }.page(1, 20)

// 按 id 删除 / 更新（update 为 mutate 风格，copy 由 KSP 内部生成）
UserTable.destroy(id)
UserTable.update(id) {
    name = "Tom"
    email = "tom@example.com"
}

// ActiveRecord
user.save()
user.delete()
```

## 📋 注解与约定

- **@Table("表名")** — 表名，缺省时按类名转 snake_case
- **@Id** — 主键字段，可 `Long?` 自增
- **@Serializable** — 若做 HTTP JSON / 序列化需加
- 列名默认按属性名转 snake_case（如 `userName` → `user_name`）

## 🔧 驱动配置

### SQLite 数据库
```toml
[default]
driver = "SQLITE"
uri = "sqlite://data/myapp.db"
debug = true
```

### PostgreSQL 数据库
```toml
[default]
driver = "POSTGRESQL"
uri = "postgresql://user:password@localhost:5432/mydb"
debug = false
maxConnections = 20
connectionTimeout = 30000
```

## 🎯 设计理念

### 1. 零配置哲学
- **约定优于配置** - 合理默认值，减少样板代码
- **注解驱动** - 声明式编程，清晰明了
- **自动适配** - 智能判断，无需手动配置

### 2. 类型安全优先
- **编译时检查** - 在编译阶段捕获错误
- **DSL 设计** - 自然语言式的查询表达
- **IDE 友好** - 完整的代码补全和提示

### 3. 现代化架构
- **组件化设计** - 可插拔，易扩展
- **异步优先** - 全面支持协程
- **性能优化** - 连接池，查询优化

## 🚀 发展路线图

### ✅ 已完成（v1 API Freeze）
- [x] Table v1 API 冻结
- [x] KSP 生成 UserTable（SqlxTableAdapter）
- [x] sqlx4k SQLite 适配
- [x] Query DSL（where/orderBy/limit/page）
- [x] DatabaseComponent、ensureTable
- [x] examples/mvc

### 📋 计划中（v1.1+）
- [ ] 数据库迁移（Migration）
- [x] PostgreSQL/MySQL 支持（sqlx4k-postgres、sqlx4k-mysql），按 database.conf 的 driver 自动选择
- [ ] 查询缓存

## 📦 架构分层

```
Controller → Logic → Table → DbContext → Driver
```

- **Table**：表级 CRUD（≈ MyBatis-Plus Mapper），手写 Facade + KSP 生成实现，单表 `get/where/list`。
- **Logic**：业务聚合层，手写，持 `DbContext` 做 JOIN / 事务。

**推荐目录结构**：

```
kotlin/
├── model/       ← 实体（@Table data class）
├── table/       ← Table Facade（手写，import table.*）
├── logic/       ← 业务逻辑
├── controller/  ← HTTP 接口
└── dto/         ← 数据传输对象
```

```kotlin
// 多对多：User + Role via user_roles
class UserLogic(private val db: DbContext = dbContext()) : DbContext by db {
    suspend fun getWithRoles(userId: Long): UserWithRoles? {
        val rows = fetchAll("""
            SELECT u.id, u.name, u.email, r.id AS role_id, r.name AS role_name
            FROM users u
            LEFT JOIN user_roles ur ON ur.user_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.id = :uid
        """.trimIndent(), mapOf("uid" to userId))
        if (rows.isEmpty()) return null
        val first = rows.first()
        val user = User(id = first.long("id"), name = first.string("name"), email = first.string("email"), status = first.int("status"), age = first.int("age"))
        val roles = rows.mapNotNull { r ->
            r.longOrNull("role_id")?.let { Role(id = it, name = r.string("role_name")) }
        }.distinctBy { it.id }
        return UserWithRoles(user, roles)
    }
}

// 调用
val user = UserLogic().getWithRoles(1)
```

## 💡 使用示例

查看完整的使用示例：
- **基础示例**: `example/User.kt` - 实体模型定义
- **CRUD 演示**: `example/DatabaseExample.kt` - 完整的操作演示
- **组件集成**: 参考 `examples/mvc` 项目

## 🛡️ Schema 演进 / Migration 边界

> **`neton-database` 不做运行时 schema 变更。**

| 维度 | 规则 |
|------|------|
| 运行时 ALTER | ❌ 禁止 |
| 启动时自动 migration | ❌ 禁止 |
| `ensureTable()` 用途 | dev / demo / ephemeral test only |
| 生产 schema 演进 | ✅ 手动执行 `sql/{dialect}/V*.sql`（唯一权威路径） |
| 未来迁移工具 | 计划中的独立 `neton-migrate` CLI（不嵌入运行时） |

完整边界规范、命令集设计、版本表、明确禁止清单见：
**[Migration Boundary Spec](https://netonframework.github.io/spec/migration)** — 必读。

## 🔗 相关模块

- **neton-core** - 核心框架模块
- **neton-http** - HTTP 服务器模块
- **neton-routing** - 路由组件模块
- **neton-ksp** - KSP 注解处理器（EntityTableProcessor 等）

---

**Neton Database - 为现代 Kotlin 应用而生的数据库模块** 🚀 