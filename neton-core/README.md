# Neton Core 模块

Neton Framework 的核心模块，提供基础的 Web 应用程序功能。

## 🏗️ 模块结构

```
neton-core/src/main/kotlin/
├── application/               # 应用程序核心
│   ├── Application.kt        # 应用程序主类
│   └── ApplicationBuilder.kt # 应用程序构建器
├── config/                   # 配置系统
│   ├── ConfigRegistry.kt     # 配置注册表
│   ├── ConfigLoader.kt       # 配置加载器
│   └── ConfigParser.kt       # 配置解析器
├── module/                   # 模块系统
│   └── NetonModule.kt       # 模块接口
├── service/                  # 服务注册
│   └── ServiceRegistry.kt    # 服务注册表
└── annotations/              # 注解定义
    ├── Controller.kt         # 控制器注解
    ├── Route.kt             # 路由注解
    └── AllowAnonymous.kt    # 匿名访问注解
```

## ✨ 核心功能

### 1. 应用程序启动
- **零配置启动**：自动加载配置、注册模块
- **命令行参数支持**：`--config-path` 和 `--env`
- **生命周期管理**：规范的启动流程

### 2. 配置系统
- **分模块配置**：每个模块对应独立配置文件
- **环境配置覆盖**：支持 dev/test/prod 环境
- **配置合并**：智能合并主配置和环境配置

### 3. 模块系统
- **静态注册**：编译时确定，无反射
- **优先级排序**：控制模块初始化顺序
- **自动配置映射**：模块名自动对应配置文件

### 4. 服务注册
- **轻量级 DI**：简单的依赖注入功能
- **类型安全**：利用 Kotlin 的 reified 泛型

## 🚀 使用示例

```kotlin
import neton.core.Neton
import neton.http.http
import neton.security.security
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        security { registerMockAuthenticator("admin", listOf("admin")) }
        routing { }
        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```

## 🎯 设计理念

1. **零反射**：所有功能基于静态注册，适配 Kotlin Native
2. **约定优于配置**：模块名自动映射配置文件
3. **模块化优先**：清晰的模块边界和职责
4. **简单易用**：最小化样板代码

## 📦 依赖关系

- **Kotlin Native**：纯原生实现，无 JVM 依赖
- **Kotlinx Coroutines**：异步编程支持
- **Kotlinx Serialization**：配置序列化

## 🔧 配置文件示例

### application.conf
```toml
[app]
name = "My Neton App"
port = 8080
debug = false
```

### routing.conf
```toml
[routing]
groups = [
  { name = "default", mount = { type = "path", value = "/" } },
  { name = "admin", mount = { type = "path", value = "/admin" } }
]
```

## 🚧 后续开发计划

- [ ] 完善文件 I/O 操作（目前使用占位符实现）
- [ ] 配置文件加载已支持 TOML（application.conf）
- [ ] 实现 HTTP 服务器集成
- [ ] 添加更多生命周期钩子
- [ ] 性能优化和内存管理

## 功能特性

- ✅ **控制器自动扫描** - 自动发现和注册控制器
- ✅ **参数绑定注解** - 支持 @PathVariable, @QueryParam, @Body 等
- ✅ **安全认证模块** - 灵活的认证和授权系统
- ✅ **模块化架构** - 可插拔的模块系统
- ✅ **配置文件支持** - TOML 配置文件加载（application.conf）

## 安全模块使用

### 1. 基本配置

```kotlin
Application.create()
    .configureSecurity {
        // 默认认证守卫
        bind(SessionGuard())
        
        // 特定路由组认证
        bind("admin", JWTGuard("your-secret-key"))
        bind("api", BasicGuard { username, password ->
            // 自定义认证逻辑
            if (username == "admin" && password == "secret") {
                UserPrincipal("admin", listOf("admin"))
            } else null
        })
    }
    .start()
```

### 2. 控制器安全注解

```kotlin
@Controller("/secure")
class SecureController {
    
    @Get("/public")
    @AllowAnonymous  // 允许匿名访问
    fun publicEndpoint(call: NetonCall): String {
        return "Public access"
    }
    
    @Get("/admin")
    @RolesAllowed("admin")  // 需要 admin 角色
    fun adminEndpoint(call: NetonCall): String {
        return "Admin only: ${call.userId()}"
    }
    
    @Get("/staff")
    @RolesAllowed("admin", "manager", "staff")  // 需要任意一个角色
    fun staffEndpoint(call: NetonCall): String {
        return "Staff access: ${call.userRoles().joinToString()}"
    }
}
```

### 3. 内置守卫类型

| 守卫类型 | 描述 | 使用场景 |
|---------|------|----------|
| `MockGuard` | 模拟认证，返回固定用户 | 开发测试 |
| `SessionGuard` | 基于会话的认证 | 传统 Web 应用 |
| `JWTGuard` | JWT Token 认证 | API 服务 |
| `BasicGuard` | HTTP Basic 认证 | 简单 API |
| `AnonymousGuard` | 永远返回 null | 公开路由 |

### 4. 在控制器中使用认证信息

```kotlin
@Get("/profile")
fun userProfile(call: NetonCall): String {
    if (!call.isAuthenticated()) {
        return "Please login"
    }
    
    val userId = call.userId()
    val roles = call.userRoles()
    val department = call.userAttribute("department")
    
    return "User: $userId, Roles: $roles, Dept: $department"
}
```

### 5. 路由组认证映射

```kotlin
// 不同路由组使用不同认证方案
configureSecurity {
    bind(SessionGuard())                    // 默认路由组
    bind("admin", JWTGuard("admin-key"))    // /admin/* 路由
    bind("api", BasicGuard(...))            // /api/* 路由  
    bind("secure", MockGuard(...))          // /secure/* 路由
}
```

## Principal 接口

```kotlin
interface Principal {
    val id: String                          // 用户ID
    val roles: List<String>                 // 用户角色
    val attributes: Map<String, Any>        // 扩展属性
    
    fun hasRole(role: String): Boolean      // 检查角色
    fun hasAnyRole(vararg roles: String): Boolean
    fun hasAllRoles(vararg roles: String): Boolean
}
```

## 完整示例

参考 `examples/multigroup` 项目中的完整安全配置示例。

---

这个 Core 模块为 Neton 框架提供了坚实的基础，体现了现代 Kotlin Native 框架的设计理念。 