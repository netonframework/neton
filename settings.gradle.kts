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
include(":neton-websocket")  // 可选 WebSocket 模块，包含默认 provider
include(":neton-http-hyper4k") // Hyper4k 适配层（Rust 引擎本体是独立仓库）
include(":neton-http-ktor")    // Ktor 兼容适配层
include(":neton-ai")         // AI 抽象层（generateText/streamText/tool loop/router/usage, OpenAi-compat + Anthropic v0.1）
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
include(":examples:cache-demo")
include(":examples:multigroup")
include(":examples:mvc")
include(":examples:backend-app")
include(":examples:neton-ai-sample")
include(":examples:bench")

// Rust 引擎仓库。没有 checkout 时本仓其余模块照常构建。
// hyper4k 是独立发布的库（com.netonstream:hyper4k），默认按坐标从仓库解析——
// 构建出的产物和使用者拿到的一致。本地联调 hyper4k 时用 -Phyper4k.local=true 打开源码替换；
// 默认关闭是为了避免「本地改了 hyper4k、neton 构建通过、发布出去却依赖未含该改动的版本」。
val useLocalHyper4k = providers.gradleProperty("hyper4k.local").orNull == "true"
require(!useLocalHyper4k || !providers.gradleProperty("hyper4k.repository").isPresent) {
    "Choose Hyper4k source substitution OR artifact verification, not both."
}
if (useLocalHyper4k) {
    require(file("../hyper4k").isDirectory) { "-Phyper4k.local=true requires ../hyper4k" }
    includeBuild("../hyper4k")
}

// netonstream 协议栈（io / http / tls / openssl / websocket）。WebSocket 集成阶段全部依赖兄弟目录的源码，
// 不发布版本（neton-docs spec/websocket.md §3）。开关显式写在 gradle.properties，不做隐式探测；
// 与发布验证用的 netonstream.repository 互斥。恢复发布前按 spec §3.4 关闭。
val useLocalNetonstream = providers.gradleProperty("netonstream.local").orNull == "true"
require(!useLocalNetonstream || !providers.gradleProperty("netonstream.repository").isPresent) {
    "Choose netonstream source substitution OR artifact verification, not both."
}
if (useLocalNetonstream) {
    for (dir in listOf("../io", "../http", "../tls", "../websocket")) {
        require(file(dir).isDirectory) { "netonstream.local=true requires $dir" }
        includeBuild(dir)
    }
    // openssl-kotlin builds only the host target unless run with -PnativeTargets=all, while tls declares all
    // ten, so substituting it breaks tls's other targets; building all ten means compiling OpenSSL with the
    // NDK, iOS SDK and MinGW toolchains. It only binds upstream OpenSSL and is not developed in this round:
    // the published com.netonstream:openssl that tls pins is used unless asked for explicitly.
    if (providers.gradleProperty("netonstream.local.openssl").orNull == "true") {
        require(file("../openssl-kotlin").isDirectory) { "netonstream.local.openssl=true requires ../openssl-kotlin" }
        includeBuild("../openssl-kotlin")
    }
}
