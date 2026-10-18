# 🚀 Neton multigroup 示例

> 展示 Neton 框架多路由组（admin/app/payment）功能的完整示例应用

## 🏃‍♂️ 快速开始

### 编译和运行
```bash
# 在项目根目录编译
./gradlew :examples:multigroup:build

# 运行应用
./gradlew :examples:multigroup:runDebugExecutableNative

# 或者构建后直接运行
./examples/multigroup/build/bin/native/debugExecutable/multigroup.kexe
```

**端点验证脚本**（构建后自动请求各路由，验证响应）：
```bash
cd examples/multigroup
./verify-endpoints.sh           # 完整构建 + 验证
./verify-endpoints.sh --skip-build   # 仅验证（跳过构建）
```

**若提示端口 8080 已被占用**，先释放端口再运行：
```bash
# 查看占用 8080 的进程
lsof -ti:8080
# 结束该进程
kill $(lsof -ti:8080)
```
或修改 `config/application.conf` 中的 `port` 为其他端口（如 8081）。

### 配置文件
应用程序会自动读取配置文件中的端口设置：
```toml
# config/application.conf
[app]
name = "multigroup Example"
port = 8080
debug = true

[server]
port = 8080
```

### 应用程序入口
```kotlin
// Main.kt - 使用 Neton 框架的标准 API
import neton.core.Neton
import neton.http.http
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {
        http { port = 8080 }
        routing { }
    }
}
```

---

这是一个展示 Neton 框架功能的 HelloWorld 示例应用。

## 🎯 新特性：带路径参数的注解

Neton 现在支持更加灵活和精确的注解语法：

### 控制器注解
```kotlin
@Controller("/api/users")  // 控制器基础路径
class UserController {
    // 控制器方法...
}
```

### HTTP 方法注解
```kotlin
@Get("/list")        // GET /api/users/list
@Post("/")           // POST /api/users
@Put("/{id}")        // PUT /api/users/{id}
@Delete("/{id}")     // DELETE /api/users/{id}
```

## 🚀 四种路由模式演示

### 1. SIMPLE 模式
```kotlin
@Controller("/user")
class UserController {
    @Get("/list")     // GET /user/list
    @Get("/profile")  // GET /user/profile
    @Get("/{id}")     // GET /user/{id}
}
```

### 2. MODULAR 模式
```kotlin
@Controller("/user/index")
class IndexController {
    @Get("/home")     // GET /user/index/home
    @Get("/about")    // GET /user/index/about
    @Get("/")         // GET /user/index
}
```

### 3. ROUTE_GROUP 模式
```kotlin
@Controller("/admin/user")
class AdminUserController {
    @Get("/list")       // GET /admin/user/list
    @Get("/dashboard")  // GET /admin/user/dashboard
    @Post("/")          // POST /admin/user
    @Delete("/{id}")    // DELETE /admin/user/{id}
}
```

### 4. FULL 模式
```kotlin
@Controller("/admin/user/profile")
class ProfileController {
    @Get("/settings")     // GET /admin/user/profile/settings
    @Get("/security")     // GET /admin/user/profile/security
    @Get("/")             // GET /admin/user/profile
    @Put("/settings")     // PUT /admin/user/profile/settings
    @Patch("/")           // PATCH /admin/user/profile
}
```

## 🔧 运行示例

1. 启动应用：
```bash
./gradlew :examples:multigroup:runReleaseExecutableNative
```

2. 访问端点：
- http://localhost:8091/ - 欢迎页面
- http://localhost:8091/user/list - SIMPLE 模式示例
- http://localhost:8091/admin/user/profile/settings - FULL 模式示例

## 📝 注解优势

### ✅ 明确性
- 必须显式声明控制器和路由处理器
- 路径参数清晰可见

### 🛡️ 安全性
- 避免意外暴露不应该作为 API 的方法
- 只有带注解的方法才会被注册为路由

### 🎯 灵活性
- 控制器可以有非路由方法
- 支持复杂的路径结构和参数

### ⚡ 性能
- 只扫描标记的类和方法
- 编译时路径验证

## 🌟 框架特性

- ✅ 启动系统 (Application + ApplicationBuilder)
- ✅ HTTP 服务 (基于 Ktor 整合)
- ✅ 路由系统 (4种模式智能路由)
- ✅ 简化注解 (@Get/@Post 等)
- ✅ 配置管理 (灵活配置系统)
- ✅ 模块架构 (清晰模块化设计)
- ✅ 性能优化 (Kotlin 2.1.0)

## 🚀 框架特色

- **零反射设计**: 纯编译时处理，运行时性能极佳
- **简化注解**: `@Get("/path")` 替代 `@Route(HttpMethod.GET, "/path")`
- **4种路由模式**: 自适应不同项目结构
- **模块化架构**: Core + Routing 清晰分离
- **Kotlin Native**: 原生编译，快速启动

## 📁 项目结构

```
multigroup/
├── src/main/kotlin/
│   ├── Main.kt                     # 应用程序入口
│   └── controller/                 # 控制器目录
│       └── IndexController.kt      # 示例控制器
├── src/main/resources/
│   ├── application.conf            # 应用程序配置（TOML）
│   └── routing.conf                # 路由配置
└── build.gradle.kts               # 构建配置
```

## ✨ 简化注解示例

### 传统写法 vs 简化写法

```kotlin
// ❌ 传统写法（繁琐）
@Route(HttpMethod.GET, "/hello")
fun hello(): String = "Hello, World!"

@Route(HttpMethod.POST, "/echo")  
fun echo(): String = "Echo!"

// ✅ 简化写法（优雅）
@Get("/hello")
fun hello(): String = "Hello, World!"

@Post("/echo")
fun echo(): String = "Echo!"
```

### 支持的简化注解

| 注解 | HTTP方法 | 示例 |
|------|----------|------|
| `@Get` | GET | `@Get("/users")` |
| `@Post` | POST | `@Post("/users")` |
| `@Put` | PUT | `@Put("/users/{id}")` |
| `@Delete` | DELETE | `@Delete("/users/{id}")` |
| `@Patch` | PATCH | `@Patch("/users/{id}")` |
| `@Options` | OPTIONS | `@Options("/users")` |
| `@Head` | HEAD | `@Head("/users")` |

### 控制器示例

```kotlin
@Controller
class IndexController {
    
    @Get("/hello")
    fun hello(): String {
        return "Hello, Neton!"
    }
    
    @Get("/api")
    fun api(): String {
        return """{"message": "Hello from API!"}"""
    }
    
    @Post("/echo")
    fun echo(): String {
        return """{"echo": "Message received!"}"""
    }
}
```

## 🛠️ 运行示例

### 编译和运行
```bash
# 在项目根目录
./gradlew build
./gradlew run

# 或使用命令行参数
./gradlew run --args="--env=dev --server.port=8081"
```

### 测试路由
```bash
# GET 请求
curl http://localhost:8080/index/hello
curl http://localhost:8080/index/api
curl http://localhost:8080/index/status

# POST 请求
curl -X POST http://localhost:8080/index/echo
```

## 🔧 4种路由模式

Neton 根据项目结构自动检测路由模式：

### 1. 简单模式 (当前示例)
- **URL**: `/{controller}/{method}`
- **目录**: `controller/IndexController.kt`
- **示例**: `/index/hello` → `IndexController.hello()`

### 2. 模块模式
- **URL**: `/{module}/{controller}/{method}`
- **目录**: `modules/user/controller/IndexController.kt`
- **示例**: `/user/index/profile` → `user.IndexController.profile()`

### 3. 路由组模式
- **URL**: `/{routeGroup}/{controller}/{method}`
- **目录**: `controller/admin/UserController.kt`
- **示例**: `/admin/user/list` → `admin.UserController.list()`

### 4. 完整模式
- **URL**: `/{routeGroup}/{module}/{controller}/{method}`
- **目录**: `modules/user/controller/admin/ManageController.kt`
- **示例**: `/admin/user/manage/list` → `user.admin.ManageController.list()`

## ⚙️ 配置说明

### application.conf
```toml
[app]
name = "multigroup Example"
port = 8080
debug = true

[server]
port = 8080
```

### routing.conf
```toml
[routing]
debug = true
```

## 📊 性能优势

| 特性 | Neton | 传统框架 |
|------|--------|----------|
| 启动时间 | < 100ms | > 1000ms |
| 内存占用 | < 20MB | > 100MB |
| 反射依赖 | ❌ 零反射 | ✅ 大量反射 |
| 编译时优化 | ✅ 完全优化 | ❌ 运行时处理 |

## 🎯 核心优势

- **开发体验**: 简化注解，代码更清晰
- **性能卓越**: 零反射，编译时优化  
- **结构灵活**: 4种模式适应不同项目
- **配置简单**: 约定优于配置
- **类型安全**: Kotlin 类型系统加持

## 🚀 下一步

1. **添加更多控制器**: 在 `controller/` 目录下创建新控制器
2. **尝试其他模式**: 创建 `modules/` 或路由组目录
3. **自定义配置**: 修改 `application.conf` 和 `routing.conf`
4. **集成更多模块**: 添加 Security、Data 等模块

---

**享受 Neton 带来的开发乐趣！** 🎉 

### 🔒 安全功能展示

```bash
# 公开端点 - 无需认证
curl http://localhost:8080/secure/public

# 受保护端点 - 需要认证
curl http://localhost:8080/secure/protected

# 管理员端点 - 需要 admin 角色  
curl http://localhost:8080/secure/admin

# 员工端点 - 需要 staff 角色
curl http://localhost:8080/secure/staff

# 用户资料 - 使用 @CurrentUser 注解
curl http://localhost:8080/secure/profile

# 可选认证端点 - 支持未认证用户访问
curl http://localhost:8080/secure/optional-auth

# 管理员专用资料 - 角色检查 + 用户注入
curl http://localhost:8080/secure/admin-profile
```

**@CurrentUser / Identity 自动注入优势**：
- 直接注入：无需手动从 HttpContext 获取用户
- 类型安全：编译时确保用户类型正确
- 零注解可用：Identity 类型参数自动注入
- 安全保障：自动处理认证检查和异常情况
- 可选支持：支持可选认证场景，灵活应对不同需求