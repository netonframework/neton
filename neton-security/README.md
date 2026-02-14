# Neton Security 模块

## 依赖

- **cryptography-kotlin** (cryptography-core + cryptography-provider-optimal)：HS256 验签，Native 通过 CommonCrypto/OpenSSL，使用 Blocking API（decodeFromByteArrayBlocking / generateSignatureBlocking）纯 ByteArray

## 概述

Neton Security 模块提供了完整的安全认证和授权功能，包括：

- **认证（Authentication）**: 用户身份验证
- **授权（Authorization）**: 基于角色和权限的访问控制
- **安全上下文**: 全局用户状态管理
- **守卫系统**: 灵活的路由保护机制

## 核心组件

### 认证系统
- `Authenticator`: 认证器接口和实现
- `Principal`: 用户主体信息
- `SecurityContext`: 安全上下文管理

### 授权系统  
- `Guard`: 守卫接口和实现
- `SecurityBuilder`: 安全配置构建器
- `SecurityRegistry`: 安全组件注册表

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
            registerMockAuthenticator("test-user", listOf("user", "admin"))
            bindDefaultGuard()
            bindAdminGuard()
        }
        routing { }

        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```

## 特性

- 🔐 多种认证方式支持
- 🛡️ 基于角色的访问控制  
- 🌐 全局安全上下文
- ⚡ 高性能的安全检查
- 🔧 灵活的配置方式 