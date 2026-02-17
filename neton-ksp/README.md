# Neton KSP

Kotlin Symbol Processing 编译器插件，为 Neton 提供编译时代码生成。

## Processors

| Processor | 注解 | 生成物 |
|-----------|------|--------|
| **ControllerProcessor** | `@Controller`、`@Get`/`@Post` 等 | 路由注册、Controller 调用桥接、泛型序列化 |
| **EntityStoreProcessor** | `@Table`、`@Id` | UserMeta、UserRowMapper、UserTable、UserExtensions |
| **NetonConfigProcessor** | `@NetonConfig` | 配置注册表、自动应用 |
| **RepositoryProcessor** | `@Repository` | Statements、RowMapper、Table、RepositoryImpl |
| **ValidationProcessor** | `@Valid` 等 | 校验代码生成 |
| **JobProcessor** | `@Job` | GeneratedJobRegistry、任务定义注册 |
| **ModuleInitializerProcessor** | KSP 选项 `neton.moduleId` | ModuleInitializer 实现、stats 统计 |

## 主路径生成（@Table 实体）

```
@Table("users") data class User(...)
    ↓
UserMeta          (EntityMeta)
UserRowMapper     (sqlx4k RowMapper)
UserTable         (object : Table<User, Long> by SqlxTableAdapter<User, Long>)
UserExtensions   (update(id){ }, save(), delete())
```

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
