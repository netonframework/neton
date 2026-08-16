import config.defaultConfigRegistry
import neton.core.Neton
import neton.http.http
import neton.redis.redis
import neton.security.security
import neton.routing.routing

/**
 * Neton multigroup - 单阶段 install DSL + @NetonConfig 业务配置
 *
 * 展示多路由组（admin/app/payment）与 mount 能力；DSL 只做基础设施安装，业务配置在 config/AppSecurityConfig
 */
fun main(args: Array<String>) {
    Neton.run(args) {

        defaultConfigRegistry()?.let { configRegistry(it) }

        http {
            port = 8080
        }

        security {
            // 业务配置由 @NetonConfig("security") AppSecurityConfig 自动应用
        }

        routing {
            println("🛣️ Routing configured - KSP will auto-generate controller routes")
        }

        // KSP 为本应用的 @Controller 生成的注册器，必须显式传入
        modules(neton.core.generated.GeneratedInitializer)

        redis {
            // keyPrefix 默认 "neton"，锁 key = neton:lock:xxx
        }

        onStart {
            println("🌟 multigroup application ready!")
            println("🌍 Visit: http://localhost:${getPort()}")
            println("📖 Available endpoints:")
            println("    GET  /                         - 首页")
            println("    GET  /admin/index              - admin 组 (mount)")
            println("    GET  /admin/index/public       - admin 公开")
            println("    GET  /app/index                - app 组 (mount)")
            println("    GET  /payment/index            - payment 模块 (default)")
            println("    GET  /admin/payment/index      - payment admin (mount)")
            println("    GET  /simple/hello             - 简单问候")
            println("    GET  /api/security/public      - 公开接口")
            println("    POST /api/products/            - 创建产品")
            println("    GET  /api/lock/{resourceId}    - 分布式锁演示")
            println("🎯 Framework ready to handle requests!")
        }
    }
}