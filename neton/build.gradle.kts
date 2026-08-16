plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

// 面向使用方的入口坐标：com.netonstream:neton。
//
// 一行依赖拉起能跑的最小应用（core + logging + http + routing），并通过 BOM 约束把
// 其余 neton-* 模块的版本钉在同一个发布版本上——使用方按需追加模块时不写版本，
// 也不会出现子模块版本错位。
//
// 不把 database / redis / cache / storage / jobs / ai 收进来：它们带原生依赖
// （sqlx4k、Redis 客户端），Kotlin/Native 静态链接会把用不到的库也编进二进制；
// 且 neton-database 没有 macosX64 目标，收进来会让 Intel Mac 连 hello world 都编不了。
kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":neton-core"))
                api(project(":neton-logging"))
                api(project(":neton-http"))
                api(project(":neton-routing"))
                api(dependencies.platform(project(":neton-bom")))
            }
        }
    }
}
