# Neton KSP

Kotlin Symbol Processing 编译器插件，为 Neton 提供编译时代码生成。

## Processors

| Processor | 注解 | 生成物 |
|-----------|------|--------|
| **ControllerProcessor** | `@Controller`、`@Get`/`@Post` 等 | 路由注册、Controller 调用桥接、泛型序列化 |
| **EntityTableProcessor** | `@Table`、`@Id` | UserMeta、UserRowMapper、UserTable/UserTableImpl、UserExtensions、UserTableDef、UserEntityMapper |
| **NetonConfigProcessor** | `@NetonConfig` | 配置注册表、自动应用 |
| **ValidationProcessor** | `@Valid` 等 | 校验代码生成 |
| **JobProcessor** | `@Job` | GeneratedJobRegistry、任务定义注册 |
| **ModuleInitializerProcessor** | KSP 选项 `neton.moduleId` | ModuleInitializer 实现、stats 统计 |

## 主路径生成（@Table 实体）

### 默认模式（KSP 全自动生成）

```
@Table("users") data class User(...)
    ↓
UserMeta           (internal, EntityMeta)
UserRowMapper      (internal, sqlx4k RowMapper)
UserTable          (object : Table<User, Long> by SqlxTableAdapter)
UserExtensions     (update(id){ }, save(), delete())
UserTableDef       (internal, Column 定义 + DSL 支持)
UserEntityMapper   (internal, Row → Entity 映射)
```

### Facade 模式（手写 Table + KSP 生成 Impl）

当用户手写 `object UserTable` 时，KSP 自动检测并切换为 Facade 模式：

```
@Table("users") data class User(...)        ← model 包
object UserTable : Table<...> by UserTableImpl  ← table 包（用户手写）
    ↓ KSP 生成
UserTableImpl      (internal, 实际实现，用户不直接使用)
UserMeta           (internal)
UserRowMapper      (internal)
UserExtensions     (自动 import Facade)
UserTableDef       (internal)
UserEntityMapper   (internal)
```

**Facade 规则（Frozen）**：

| 规则 | 说明 |
|------|------|
| Facade 形态 | 必须是 `object`，否则编译期报错 |
| 命名约定 | Facade: `XxxTable`（用户手写），实现: `XxxTableImpl`（KSP internal） |
| 包名约定 | Facade 放 `table` 包，实体放 `model` 包 |
| 可见性 | `XxxTableImpl` 为 `internal`，Facade 与实体须在同一 Gradle module |
| 向后兼容 | 不写 Facade 则 KSP 直接生成 `XxxTable`（public），行为不变 |

**Facade 写法**：

```kotlin
// table/SystemUserTable.kt
package table

import model.SystemUser
import model.SystemUserTableImpl
import neton.database.api.Table

object SystemUserTable : Table<SystemUser, Long> by SystemUserTableImpl
```

**好处**：
- IDE `Go to Definition` 跳到用户手写的 `SystemUserTable.kt`
- 可在 Facade 中添加表级便捷方法
- `import table.SystemUserTable` 语义清晰，表达架构分层

## ControllerProcessor 泛型序列化（beta1 新增）

Kotlin/Native 下 Ktor 的 `guessSerializer()` 无法处理泛型 `@Serializable` 类型（如 `PageResponse<UserVO>`）。
ControllerProcessor 在编译期检测返回类型，自动生成显式序列化代码：

```kotlin
// 编译期生成示例
val _r = ctrl.page(pageNum, pageSize)
return JsonContent(Json.encodeToString(PageResponse.serializer(UserVO.serializer()), _r))
```

支持：
- 单层泛型：`PageResponse<UserVO>`
- 嵌套泛型：`ApiResponse<PageResponse<UserVO>>`
- 非泛型 `@Serializable`：`LoginResponse`
- `List<T>`：`List<UserVO>`

## 依赖

```kotlin
dependencies {
    add("kspMacosArm64", project(":neton-ksp"))
}
```

## 输出目录

- 默认：`build/generated/ksp/<target>/<sourceSet>/kotlin/`
- mvc 将 KSP 输出纳入 commonMain 以共享生成代码
