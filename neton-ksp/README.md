# Neton KSP

Kotlin Symbol Processing 编译器插件，为 Neton 提供编译时代码生成。

## Processors

| Processor | 注解 | 生成物 |
|-----------|------|--------|
| **ControllerProcessor** | `@Controller`、`@Get`/`@Post` 等 | 路由注册、Controller 调用桥接 |
| **EntityStoreProcessor** | `@Table`、`@Id` | UserMeta、UserRowMapper、UserTable、UserExtensions |
| **NetonConfigProcessor** | `@NetonConfig` | 配置注册表、自动应用 |
| **RepositoryProcessor** | `@Repository` | Statements、RowMapper、Table、RepositoryImpl |
| **ValidationProcessor** | `@Valid` 等 | 校验代码生成 |

## 主路径生成（@Table 实体）

```
@Table("users") data class User(...)
    ↓
UserMeta          (EntityMeta)
UserRowMapper     (sqlx4k RowMapper)
UserTable         (object : Table<User, Long> by SqlxTableAdapter<User, Long>)
UserExtensions   (update(id){ }, save(), delete())
```

## 依赖

```kotlin
dependencies {
    add("kspMacosArm64", project(":neton-ksp"))
}
```

## 输出目录

- 默认：`build/generated/ksp/<target>/<sourceSet>/kotlin/`
- mvc 将 KSP 输出纳入 commonMain 以共享生成代码
