# Neton Security 模块

## 依赖

- **cryptography-kotlin** (cryptography-core + cryptography-provider-optimal)：HS256 验签，Native 通过 CommonCrypto/OpenSSL，使用 Blocking API（decodeFromByteArrayBlocking / generateSignatureBlocking）纯 ByteArray

## 概述

Neton Security 模块提供了完整的安全认证和授权功能，基于 Identity 体系（v1.2），包括：

- **认证（Authentication）**：用户身份验证，返回 `Identity`
- **授权（Authorization）**：基于角色和权限的访问控制
- **Identity 体系**：`Identity`（接口） → `IdentityUser`（默认实现），包含 `userId: UserId`、`roles: Set<String>`、`permissions: Set<String>`
- **守卫系统**：灵活的路由保护机制（DefaultGuard、AdminGuard、RoleGuard、CustomGuard 等）

## 核心组件

### 认证系统
- `Authenticator`：认证器接口和实现
  - `JwtAuthenticatorV1`：底层 JWT 解析实现（HS256，解析 sub/roles/perms）
  - `JwtAuthenticatorAdapter`：桥接 neton-core `Authenticator` 接口，委托 V1 执行
  - `MockAuthenticatorAdapter`：桥接 Mock 认证器
  - `SessionAuthenticatorAdapter`、`BasicAuthenticatorAdapter`：占位
- `Identity`：用户身份接口，继承 neton-core Identity，新增 `userId: UserId`
- `IdentityUser`：Identity 默认数据类实现
- `SecurityContext`：安全上下文管理（辅助用途）

### 授权系统
- `Guard`：守卫接口（实现 neton-core Guard.checkPermission）
- `DefaultGuardImpl`、`AdminGuardImpl`、`RoleGuardImpl`、`AnonymousGuardImpl`
- `SecurityBuilderImpl`：安全配置构建器实现
- `AuthenticationContextImpl`：认证上下文实现
- `SecurityRegistry`：安全组件注册表

### 命名规范
- `*Adapter`：桥接两个不同接口（如 neton-core `RequestContext` ↔ neton-security `RequestContext`）
- `*Impl`：同一接口的标准实现

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
                userId = "test-user",
                roles = setOf("user", "admin"),
                permissions = setOf("system:user:edit")
            )
            bindDefaultGuard()
        }
        routing { }

        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```

## Identity 继承链

```
neton-core:     Identity { id: String, roles: Set, permissions: Set }
                    ↑
neton-security: Identity { userId: UserId; override val id = userId.value.toString() }
                    ↑
                IdentityUser(userId, roles, permissions)
```

## 特性

- 多种认证方式支持（JWT、Mock、Session、Basic）
- 基于角色和权限的访问控制
- `@Permission` 注解驱动权限检查（permission implies auth：标注 @Permission 隐含强制认证）
- `PermissionEvaluator` 可扩展权限评估
- 请求级 Identity 存储（HttpContext.attributes）
- 高性能的安全检查（编译期代码生成）
- Kotlin/Native 原生支持
- 契约测试覆盖：SecurityPipelineContractTest（15 条）、JwtAuthenticatorAdapterContractTest（6 条）
