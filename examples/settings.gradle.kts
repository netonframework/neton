rootProject.name = "neton-examples"

// 复用上级 Neton 仓库的版本目录（libs.*）
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// 包含示例项目
include("helloworld")
include("multigroup")
include("mvc")
include("redis-sample")
include("bench")

// 包含上级目录的 Neton 模块
include(":neton-core")
project(":neton-core").projectDir = file("../neton-core")

include(":neton-logging")
project(":neton-logging").projectDir = file("../neton-logging")

include(":neton-routing")
project(":neton-routing").projectDir = file("../neton-routing")

include(":neton-security")
project(":neton-security").projectDir = file("../neton-security")

include(":neton-http")
project(":neton-http").projectDir = file("../neton-http")

include(":neton-ksp")
project(":neton-ksp").projectDir = file("../neton-ksp")

include(":neton-database")
project(":neton-database").projectDir = file("../neton-database")

include(":neton-redis")
project(":neton-redis").projectDir = file("../neton-redis") 