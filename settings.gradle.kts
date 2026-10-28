pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}


rootProject.name = "neton"

// 🚀 Neton Framework - 现代化平铺模块结构
include(":neton-logging")    // 地基模块：Logger API（neton.logging）+ 实现（neton.logging.internal，单模块内分层）
include(":neton-core")       // 主框架模块
include(":neton-http")       // HTTP 集成与内置 DefaultHttpAdapter
include(":neton-websocket")  // 可选 WebSocket 模块（beta 发布），默认 provider 为内置 netonstream websocket
// Geario4k, Hyper4k and Ktor adapters live in independent repositories.
// Optional AI integration is independently versioned in netonframework/neton-ai.
include(":neton-routing")    // 路由组件模块
include(":neton-security")   // 安全组件模块
include(":neton-redis")      // Redis 客户端模块
include(":neton-cache")      // 统一缓存抽象（L1+L2，强绑定 neton-redis）
include(":neton-database")   // 数据库模块
include(":neton-ksp")        // KSP 编译器插件
include(":neton-validation") // 校验模块（内建 Konform，仅服务 Neton）
include(":neton-storage")    // 统一存储抽象（Local + S3，借鉴 OpenDAL Operator）
include(":neton-jobs")       // 定时任务调度（cron + fixedRate，SINGLE_NODE/ALL_NODES）
include(":neton-bom")        // 版本对齐清单（java-platform）
include(":neton")            // 使用方入口：core + logging + http + routing + BOM 约束

// 示例项目
include(":examples:helloworld")
include(":examples:sse-demo")
include(":examples:netonstream-hello")
include(":examples:websocket-echo")
include(":examples:cache-demo")
include(":examples:multigroup")
include(":examples:mvc")
include(":examples:backend-app")
include(":examples:bench")
include(":examples:release-gate")
include(":examples:ksp-incremental")
include(":examples:api-compat")

// Hyper4k and Ktor compatibility adapters are independent repositories.
// They consume published Neton contracts and are not framework release gates.

// netonstream 协议栈（io / http / tls / openssl / websocket）与 neton-websocket 自 1.0.0-beta24 起都按 beta 发布到
// Maven Central（neton-docs spec/websocket.md §3）。netonstream.local=true 只是开发期的源码替换：直接用兄弟目录的
// 源码联调，开关显式写在 gradle.properties，不做隐式探测；与发布验证用的 netonstream.repository 互斥，
// 发布任务在它开启时拒绝执行（RELEASING.md）。
val useLocalNetonstream = providers.gradleProperty("netonstream.local").orNull == "true"
require(!useLocalNetonstream || !providers.gradleProperty("netonstream.repository").isPresent) {
    "Choose netonstream source substitution OR artifact verification, not both."
}
if (useLocalNetonstream) {
    val sourceRoot = providers.gradleProperty("netonstream.sourcesDir").orNull ?: ".."
    for (name in listOf("io", "http", "tls", "websocket")) {
        val dir = "$sourceRoot/$name"
        require(file(dir).isDirectory) { "netonstream.local=true requires $dir" }
        includeBuild(dir)
    }
    // Declare all variants with -PnativeTargets=all, then request only host test tasks.
    // Compiling a foreign target still requires its SDK/toolchain.
    if (providers.gradleProperty("netonstream.local.openssl").orNull == "true") {
        val dir = "$sourceRoot/openssl-kotlin"
        require(file(dir).isDirectory) { "netonstream.local.openssl=true requires $dir" }
        includeBuild(dir)
    }
}
