# Neton Core 模块

Neton Framework 的核心模块，提供基础的 Web 应用程序功能。

## 模块结构

```
neton-core/src/commonMain/kotlin/
├── application/               # 应用程序核心
│   ├── Application.kt        # 应用程序主类
│   └── ApplicationBuilder.kt # 应用程序构建器
├── config/                   # 配置系统
│   ├── ConfigRegistry.kt     # 配置注册表
│   ├── ConfigLoader.kt       # 配置加载器
│   └── ConfigParser.kt       # 配置解析器
├── interfaces/               # 核心接口
│   ├── SecurityInterfaces.kt # Identity, Authenticator, Guard, PermissionEvaluator
│   ├── RequestEngine.kt      # RouteDefinition, RouteHandler
│   ├── SecurityBuilder.kt    # SecurityBuilder, SecurityConfiguration
│   └── RouteGroupSecurityConfigs.kt  # 路由组安全配置
├── annotations/              # 注解定义
│   ├── SecurityAnnotations.kt  # @AllowAnonymous, @RequireAuth, @RolesAllowed, @Permission, @CurrentUser
│   └── Controller.kt         # @Controller, HTTP 方法注解
├── security/                 # 安全默认实现
│   ├── DisabledSecurity.kt   # AllowAllGuard, RequireIdentityGuard
│   └── SecurityContext.kt    # 安全上下文
├── mock/                     # Mock 实现
│   └── MockImplementations.kt # MockIdentity, MockAuthenticator, MockGuard
└── module/                   # 模块系统
    └── ModuleInitializer.kt   # 模块初始化器接口（moduleId, stats, dependsOn）
```

## 核心功能

### 1. 应用程序启动
- **零配置启动**：自动加载配置、注册模块
- **命令行参数支持**：`--config-path` 和 `--env`
- **生命周期管理**：规范的启动流程

### 2. 配置系统
- **分模块配置**：每个模块对应独立配置文件
- **环境配置覆盖**：支持 dev/test/prod 环境
- **配置合并**：智能合并主配置和环境配置

### 3. 模块系统（ModuleInitializer）
- **静态注册**：编译时确定，无反射
- **拓扑排序**：按 `dependsOn` 声明自动排序，循环依赖 fail-fast
- **自动配置映射**：模块名自动对应配置文件
- **启动统计日志**：
  - `modules.loaded`：版本 + 模块列表概览
  - `module.initialized`：每个模块的 stats（routes、validators、jobs 等）
  - `modules.summary`：汇总统计（totalModules、totalRoutes 等）

### 4. 安全接口层（v1.2）

neton-core 定义安全抽象接口，所有下游模块依赖此层：

- **Identity**：用户身份接口（id、roles、permissions）
- **Authenticator**：认证器接口（authenticate → Identity?）
- **Guard**：守卫接口（checkPermission → Boolean）
- **PermissionEvaluator**：权限评估器（fun interface）
- **SecurityAttributes**：属性常量（IDENTITY）

## 使用示例

```kotlin
import neton.core.Neton
import neton.http.http
import neton.security.security
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        security {
            registerMockAuthenticator(
                name = "mock",
                userId = "admin",
                roles = setOf("admin"),
                permissions = setOf("system:manage")
            )
        }
        routing { }
        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```

## 安全注解

```kotlin
@Controller("/secure")
class SecureController {

    @Get("/public")
    @AllowAnonymous
    fun publicEndpoint(): String = "Public access"

    @Get("/admin")
    @RequireAuth
    @Permission("admin:dashboard:view")
    fun adminEndpoint(@CurrentUser identity: Identity): String {
        return "Admin: ${identity.id}, roles: ${identity.roles}"
    }

    @Get("/profile")
    @RequireAuth
    fun profile(identity: Identity): String {
        // Identity 类型自动注入，无需 @CurrentUser
        return "User: ${identity.id}"
    }
}
```

### 安全注解一览

| 注解 | 说明 |
|------|------|
| `@AllowAnonymous` | 允许匿名访问，优先级最高 |
| `@RequireAuth` | 要求认证 |
| `@RolesAllowed("admin", "editor")` | 需要指定角色之一 |
| `@Permission("system:user:edit")` | 需要指定权限 |
| `@CurrentUser` | 注入当前 Identity（可省略，类型自动注入） |

### Identity 接口

```kotlin
interface Identity {
    val id: String
    val roles: Set<String>
    val permissions: Set<String>

    fun hasRole(role: String): Boolean
    fun hasAnyRole(vararg roles: String): Boolean
    fun hasAllRoles(vararg roles: String): Boolean
    fun hasPermission(p: String): Boolean
    fun hasAnyPermission(vararg ps: String): Boolean
    fun hasAllPermissions(vararg ps: String): Boolean
}
```

## 设计理念

1. **零反射**：所有功能基于静态注册，适配 Kotlin Native
2. **约定优于配置**：模块名自动映射配置文件
3. **模块化优先**：清晰的模块边界和职责
4. **简单易用**：最小化样板代码

## 依赖关系

- **Kotlin Native**：纯原生实现，无 JVM 依赖
- **Kotlinx Coroutines**：异步编程支持
- **Kotlinx Serialization**：配置序列化

## 配置文件示例

### application.conf
```toml
[application]
name = "My Neton App"
debug = false

[server]
host = "0.0.0.0"
port = 8080
```

### routing.conf
```toml
[[groups]]
group = "admin"
mount = "/admin"
requireAuth = true
allowAnonymous = ["/login"]

[[groups]]
group = "app"
mount = "/app"
```

## 完整示例

参考 `examples/multigroup` 项目中的完整安全配置示例。
