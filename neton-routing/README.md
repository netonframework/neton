# Neton Routing 模块

## 概述

提供路由匹配、参数绑定和 KSP 自动路由注册，通过 `routing { }` install DSL 使用。

## 使用示例

```kotlin
import neton.core.Neton
import neton.http.http
import neton.security.security
import neton.routing.routing

fun main(args: Array<String>) {
    Neton.run(args) {

        http { port = 8080 }
        security { registerMockAuthenticator("admin", listOf("admin")) }
        routing {
            // KSP 自动生成并注册 @Controller 路由，此处可选配置
        }

        onStart { println("Ready at http://localhost:${getPort()}") }
    }
}
```
