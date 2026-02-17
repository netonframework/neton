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
include(":neton-http")       // HTTP 组件模块
include(":neton-routing")    // 路由组件模块
include(":neton-security")   // 安全组件模块
include(":neton-redis")      // Redis 客户端模块
include(":neton-cache")      // 统一缓存抽象（L1+L2，强绑定 neton-redis）
include(":neton-database")   // 数据库模块
include(":neton-ksp")        // KSP 编译器插件
include(":neton-validation") // 校验模块（内建 Konform，仅服务 Neton）
include(":neton-storage")    // 统一存储抽象（Local + S3，借鉴 OpenDAL Operator）
include(":neton-jobs")       // 定时任务调度（cron + fixedRate，SINGLE_NODE/ALL_NODES）

// 示例项目
include(":examples:helloworld")
include(":examples:multigroup")
include(":examples:mvc")
include(":examples:redis-sample")
include(":examples:backend-app")
